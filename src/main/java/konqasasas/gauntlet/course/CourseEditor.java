package konqasasas.gauntlet.course;

import java.io.IOException;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import konqasasas.gauntlet.GroundGeometry;
import konqasasas.gauntlet.play.*;
import konqasasas.gauntlet.ui.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** /course: GUI for creating and editing course definitions. Builders only. */
public final class CourseEditor implements Listener,CommandExecutor,TabCompleter {
    static final String ADMIN="gauntlet.admin";
    private static final Particle.DustOptions SELECTION=new Particle.DustOptions(Color.YELLOW,1f),
            COURSE=new Particle.DustOptions(Color.AQUA,1f),START=new Particle.DustOptions(Color.LIME,1.5f),
            GOAL=new Particle.DustOptions(Color.WHITE,.8f),TRIGGER=new Particle.DustOptions(Color.ORANGE,.8f);
    final JavaPlugin plugin;
    final CourseStore store;
    final TemplateStore templates;
    final CourseItems items;
    final ChatPrompts prompts;
    final Participants participants;
    private final CourseWand wand;
    private final Selections selections=new Selections();
    private final ItemMenus itemMenus;
    private final TriggerMenus triggerMenus;
    /** The course each builder last opened, shown while holding the wand. */
    private final Map<UUID,String> editing=new HashMap<>();
    private BukkitTask outlines;

    public CourseEditor(JavaPlugin plugin,CourseStore store,TemplateStore templates,CourseItems items,NamespacedKey toolKey,Participants participants) {
        this.plugin=plugin; this.store=store; this.templates=templates; this.items=items; this.participants=participants;
        wand=new CourseWand(toolKey); prompts=new ChatPrompts(plugin);
        itemMenus=new ItemMenus(this); triggerMenus=new TriggerMenus(this,itemMenus);
    }
    public void start() {
        var pm=plugin.getServer().getPluginManager();
        pm.registerEvents(this,plugin); pm.registerEvents(prompts,plugin); pm.registerEvents(new Menu.Events(),plugin);
        PluginCommand command=Objects.requireNonNull(plugin.getCommand("course"));
        command.setExecutor(this); command.setTabCompleter(this);
        outlines=Bukkit.getScheduler().runTaskTimer(plugin,this::showOutlines,10,10);
        for(String invalid:store.invalidFiles()) plugin.getLogger().severe("Skipped invalid course file (left untouched): "+invalid);
    }
    public void stop() {
        if(outlines!=null) outlines.cancel();
        // Menus hold lambdas from this plugin instance; never leave them open across a reload.
        for(Player player:Bukkit.getOnlinePlayers())
            if(player.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu) player.closeInventory();
        prompts.clear(); selections.clear(); editing.clear();
    }

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!(sender instanceof Player player)) { sender.sendMessage("ゲーム内で /course を実行してください。"); return true; }
        if(args.length==0) { if(admin(player)) openList(player); }
        // Temporary entry points until the course selection feature exists.
        else if(args.length==2 && args[0].equalsIgnoreCase("play")) participants.enter(player,args[1].toLowerCase(Locale.ROOT),false);
        else if(args.length==2 && args[0].equalsIgnoreCase("practice")) participants.enter(player,args[1].toLowerCase(Locale.ROOT),true);
        else if(args.length==1 && args[0].equalsIgnoreCase("leave")) participants.leave(player);
        else Icons.tell(player,"/course play <ID> で本番、/course practice <ID> で練習、/course leave で終了"+(player.hasPermission(ADMIN) ? "、/course で編集GUI" : ""));
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        if(args.length==1) return List.of("play","practice","leave").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if(args.length==2 && (args[0].equalsIgnoreCase("play") || args[0].equalsIgnoreCase("practice"))) {
            boolean admin=sender.hasPermission(ADMIN);
            return store.all().stream().filter(c -> admin || c.status()==CourseStatus.DONE).map(Course::id)
                    .filter(id -> id.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
    boolean admin(Player player) {
        if(player.hasPermission(ADMIN)) return true;
        Icons.tell(player,"コースの編集は運営のみ使用できます。"); return false;
    }

    // ---- Course list ----
    void openList(Player player) {
        Menu menu=new Menu(6,"コース一覧");
        List<Course> courses=store.all();
        for(int i=0;i<Math.min(courses.size(),45);i++) {
            Course course=courses.get(i);
            menu.button(i,courseIcon(course),e -> openEdit(player,course.id()));
        }
        List<String> createLore=new ArrayList<>(List.of("IDを入力して作成","今の杖の選択範囲があれば範囲に使う"));
        if(courses.size()>45) createLore.add("※46件目以降は未表示（"+courses.size()+"件）");
        menu.button(49,Icons.icon(Material.WRITABLE_BOOK,"新規作成",createLore),e -> askNewId(player));
        menu.button(51,Icons.icon(Material.CHEST,"アイテムテンプレート",List.of("トリガーや初期装備で渡すアイテム")),e -> itemMenus.openTemplates(player,() -> openList(player)));
        menu.open(player);
    }
    private ItemStack courseIcon(Course course) {
        Material material=switch(course.status()) { case DRAFT -> Material.PAPER; case TESTING -> Material.MAP; case DONE -> Material.FILLED_MAP; };
        List<String> lore=new ArrayList<>(List.of("ID："+course.id(),"状態："+course.status().label,"作成者："+authorName(course)));
        warnings(course).forEach(p -> lore.add("⚠ "+p));
        lore.add("クリックで編集");
        return Icons.icon(material,course.name(),lore);
    }
    private void askNewId(Player player) {
        prompts.ask(player,"新しいコースのIDを入力してください（英小文字・数字・_・-、32文字まで）",text -> {
            if(!admin(player)) return;
            String id=text.toLowerCase(Locale.ROOT);
            if(!CourseStore.validId(id)) { Icons.tell(player,"IDに使えない文字があります："+text); return; }
            Course course=Course.create(id,player.getUniqueId(),player.getWorld().getUID());
            Selections.Selected selected=selections.selected(player.getUniqueId());
            if(selected!=null) course=course.withRegion(selected.world(),selected.region());
            try { store.create(course); }
            catch(IOException e) { failure(player,"コース "+id+" を作成できませんでした（同じIDのファイルがあるか、保存に失敗）。",e); return; }
            Icons.tell(player,"コース "+id+" を作成しました。");
            openEdit(player,id);
        });
    }
    /** Course problems plus references to deleted templates. */
    List<String> warnings(Course course) {
        List<String> warnings=new ArrayList<>(course.problems());
        boolean missing=course.loadout().stream().anyMatch(r -> r!=null && !items.exists(r))
                || course.triggers().stream().flatMap(t -> t.actions().stream()).anyMatch(a -> a.item()!=null && !items.exists(a.item()));
        if(missing) warnings.add("削除されたテンプレートを使っている");
        return warnings;
    }

    // ---- Course editing ----
    void openEdit(Player player,String id) {
        Course course=store.get(id);
        if(course==null) { Icons.tell(player,"コース "+id+" は削除されています。"); openList(player); return; }
        editing.put(player.getUniqueId(),id);
        Menu menu=new Menu(4,"編集："+course.name());
        List<String> info=new ArrayList<>(List.of("ID："+course.id(),"作成者："+authorName(course),"ワールド："+worldName(course)));
        if(course.problems().isEmpty()) info.add("✔ 遊べる状態です");
        warnings(course).forEach(p -> info.add("⚠ "+p));
        menu.label(4,Icons.icon(Material.BOOK,course.name(),info));
        menu.button(10,Icons.icon(Material.NAME_TAG,"名前を変更",List.of("現在："+course.name())),
                e -> prompts.ask(player,"新しいコース名を入力してください（"+Course.MAX_NAME_LENGTH+"文字まで）",text -> {
                    if(text.isBlank() || text.length()>Course.MAX_NAME_LENGTH) { Icons.tell(player,"名前は1〜"+Course.MAX_NAME_LENGTH+"文字にしてください。"); return; }
                    update(player,id,c -> c.withName(text));
                }));
        menu.button(11,Icons.icon(Material.MAP,"範囲を設定",List.of(
                "現在："+(course.region()==null ? "未設定" : course.region().describe()),"クリック：杖の選択範囲をコース範囲にする")),
                e -> setRegion(player,id));
        menu.button(12,Icons.icon(Material.RECOVERY_COMPASS,"スタート地点を設定",List.of(
                "現在："+(course.start()==null ? "未設定" : course.start().describe()),"クリック：今の立ち位置と向きをスタートにする","範囲の中で接地している必要があります")),
                e -> setStart(player,id));
        menu.button(13,Icons.icon(Material.CLOCK,"制限時間",List.of(
                "現在："+(course.timeLimitSeconds()==0 ? "なし" : course.timeLimitSeconds()+"秒"),
                "左クリック：+1秒　右クリック：-1秒","Shift＋クリック：±10秒","0秒で制限なし")),
                e -> adjustTime(player,id,e));
        menu.button(14,Icons.icon(statusIcon(course.status()),"状態："+course.status().label,List.of("クリックで切り替え","下書き → テスト中 → 完成","参加者は「完成」だけ遊べる")),
                e -> update(player,id,c -> c.withStatus(c.status().next())));
        menu.button(15,Icons.icon(Material.ENDER_PEARL,"スタート地点へ移動",List.of("スタートの位置と向きへ移動")),
                e -> teleportToStart(player,id));
        menu.button(16,Icons.icon(Material.BREEZE_ROD,"範囲指定の杖を受け取る",List.of("左クリック：1点目　右クリック：2点目","手に持つと範囲・スタート・ゴール・トリガーを表示")),
                e -> giveWand(player));
        List<String> goalLore=new ArrayList<>(List.of("登録数："+course.goals().size(),"クリック：足元のダイヤモンドブロックを登録","登録済みの上でクリックすると解除"));
        course.goals().stream().limit(5).forEach(g -> goalLore.add("・"+g.describe()));
        menu.button(19,Icons.icon(Material.DIAMOND_BLOCK,"ゴールを登録／解除",goalLore),e -> toggleGoal(player,id));
        menu.button(20,Icons.icon(Material.HEAVY_WEIGHTED_PRESSURE_PLATE,"足元のトリガーを編集",List.of("鉄の感圧板の上で押す","未登録なら登録して編集画面を開く")),
                e -> triggerMenus.openAtFeet(player,id));
        menu.button(21,Icons.icon(Material.REPEATER,"トリガー一覧",List.of("登録数："+course.triggers().size())),e -> triggerMenus.openList(player,id));
        List<String> loadoutLore=new ArrayList<>();
        for(int i=0;i<Course.COURSE_SLOTS;i++) loadoutLore.add((i+1)+"番："+items.name(course.loadout().get(i)));
        menu.button(22,Icons.icon(Material.ARMOR_STAND,"初期装備",loadoutLore),e -> itemMenus.openLoadout(player,id));
        menu.button(23,Icons.icon(course.failLava() ? Material.LAVA_BUCKET : Material.BUCKET,"失敗条件：マグマ "+(course.failLava() ? "オン" : "オフ"),List.of("クリックで切り替え")),
                e -> update(player,id,c -> c.edit().failLava(!c.failLava()).build()));
        menu.button(24,Icons.icon(course.failOutside() ? Material.STRUCTURE_VOID : Material.GLASS,"失敗条件：範囲外 "+(course.failOutside() ? "オン" : "オフ"),List.of("クリックで切り替え","死亡・時間切れは常に失敗")),
                e -> update(player,id,c -> c.edit().failOutside(!c.failOutside()).build()));
        menu.button(25,Icons.icon(Material.CHEST,"アイテムテンプレート",List.of("トリガーや初期装備で渡すアイテム")),e -> itemMenus.openTemplates(player,() -> openEdit(player,id)));
        menu.button(27,Icons.icon(Material.ARROW,"一覧へ戻る",List.of()),e -> openList(player));
        List<String> playLore=course.problems().isEmpty() ? List.of("左クリック：本番（記録が残る）","右クリック：練習","終了：/course leave") : List.of("⚠ 未設定の項目があります");
        menu.button(31,Icons.icon(Material.NETHER_STAR,"参加者モードで遊ぶ",playLore),
                e -> { if(admin(player)) participants.enter(player,id,e.isRightClick()); });
        menu.button(35,Icons.icon(Material.BARRIER,"コースを削除",List.of("確認画面が開きます")),e -> openDelete(player,id));
        menu.open(player);
    }
    private static Material statusIcon(CourseStatus status) {
        return switch(status) { case DRAFT -> Material.GRAY_DYE; case TESTING -> Material.YELLOW_DYE; case DONE -> Material.LIME_DYE; };
    }
    /** Saves a change; false (already reported) when the course is gone or the save failed. */
    boolean save(Player player,String id,UnaryOperator<Course> change) {
        if(!admin(player)) return false;
        Course course=store.get(id);
        if(course==null) { Icons.tell(player,"コース "+id+" は削除されています。"); openList(player); return false; }
        try { store.save(change.apply(course)); return true; }
        catch(IOException|RuntimeException e) { failure(player,"コース "+id+" を保存できませんでした。",e); return false; }
    }
    private void update(Player player,String id,UnaryOperator<Course> change) {
        if(save(player,id,change)) openEdit(player,id);
    }
    private void setRegion(Player player,String id) {
        Selections.Selected selected=selections.selected(player.getUniqueId());
        if(selected==null) { Icons.tell(player,"先に杖で同じワールドの2点を選んでください。"); return; }
        update(player,id,c -> c.withRegion(selected.world(),selected.region()));
        Icons.tell(player,"範囲を設定しました："+selected.region().describe());
    }
    private void setStart(Player player,String id) {
        Course course=store.get(id); if(course==null) return;
        Location at=player.getLocation();
        if(!at.getWorld().getUID().equals(course.world())) { Icons.tell(player,"コースと同じワールドで設定してください（"+worldName(course)+"）。"); return; }
        if(course.region()==null) { Icons.tell(player,"先に範囲を設定してください。"); return; }
        Spot spot=new Spot(at.getX(),at.getY(),at.getZ(),at.getYaw(),at.getPitch());
        if(!course.contains(spot)) { Icons.tell(player,"スタート地点はコース範囲の中にしてください。"); return; }
        if(!player.isOnGround()) { Icons.tell(player,"接地した状態で設定してください。"); return; }
        update(player,id,c -> c.withStart(spot));
        Icons.tell(player,"スタート地点を設定しました："+spot.describe());
    }
    private void toggleGoal(Player player,String id) {
        Course course=store.get(id); if(course==null) return;
        if(!player.getWorld().getUID().equals(course.world())) { Icons.tell(player,"コースと同じワールドで設定してください（"+worldName(course)+"）。"); return; }
        if(!player.isOnGround()) { Icons.tell(player,"ダイヤモンドブロックの上に立って設定してください。"); return; }
        Location at=player.getLocation();
        Block goal=GroundGeometry.supporting(at.getWorld(),player.getBoundingBox(),at.getY()).stream()
                .filter(b -> b.getType()==Material.DIAMOND_BLOCK).findFirst().orElse(null);
        if(goal==null) { Icons.tell(player,"足元がダイヤモンドブロックではありません。"); return; }
        if(course.region()==null || !course.region().contains(goal.getX(),goal.getY(),goal.getZ())) { Icons.tell(player,"ゴールはコース範囲の中にしてください。"); return; }
        BlockPos pos=new BlockPos(goal.getX(),goal.getY(),goal.getZ());
        boolean removing=course.goals().contains(pos);
        update(player,id,c -> c.toggleGoal(pos));
        Icons.tell(player,(removing ? "ゴールを解除しました：" : "ゴールを登録しました：")+pos.describe());
    }
    private void adjustTime(Player player,String id,InventoryClickEvent event) {
        int delta=(event.isShiftClick() ? 10 : 1)*(event.isRightClick() ? -1 : 1);
        update(player,id,c -> c.withTimeLimit(c.timeLimitSeconds()+delta));
    }
    private void teleportToStart(Player player,String id) {
        Course course=store.get(id); if(course==null) return;
        if(course.start()==null) { Icons.tell(player,"スタート地点が未設定です。"); return; }
        Spot s=course.start();
        teleport(player,course,s.x(),s.y(),s.z(),s.yaw(),s.pitch());
    }
    void teleport(Player player,Course course,double x,double y,double z,float yaw,float pitch) {
        World world=Bukkit.getWorld(course.world());
        if(world==null) { Icons.tell(player,"コースのワールドが読み込まれていません。"); return; }
        player.closeInventory();
        if(!player.teleport(new Location(world,x,y,z,yaw,pitch),PlayerTeleportEvent.TeleportCause.PLUGIN)) Icons.tell(player,"移動が拒否されました。");
    }
    private void giveWand(Player player) {
        if(!admin(player)) return;
        for(ItemStack item:player.getInventory().getContents()) if(wand.is(item)) { Icons.tell(player,"杖はすでに持っています。"); return; }
        if(!player.getInventory().addItem(wand.create()).isEmpty()) Icons.tell(player,"インベントリに空きを作ってください。");
        else Icons.tell(player,"範囲指定の杖を渡しました。");
    }
    private void openDelete(Player player,String id) {
        Menu menu=new Menu(3,"削除の確認："+id);
        menu.button(11,Icons.icon(Material.RED_CONCRETE,"削除する",List.of("コース "+id+" を削除します","ファイルは courses/deleted/ に退避されます")),e -> {
            if(!admin(player)) return;
            try { store.delete(id); Icons.tell(player,"コース "+id+" を削除しました。"); editing.values().removeIf(id::equals); }
            catch(IOException e2) { failure(player,"コース "+id+" を削除できませんでした。",e2); }
            openList(player);
        });
        menu.button(15,Icons.icon(Material.LIME_CONCRETE,"やめる",List.of()),e -> openEdit(player,id));
        menu.open(player);
    }

    // ---- Wand ----
    @EventHandler(priority=EventPriority.HIGHEST)
    public void wandClick(PlayerInteractEvent event) {
        // The off-hand pass after a main-hand wand click must not place or use the off-hand item.
        if(event.getHand()==EquipmentSlot.OFF_HAND && wand.is(event.getPlayer().getInventory().getItemInMainHand())) { event.setCancelled(true); return; }
        if(!wand.is(event.getItem())) return;
        event.setCancelled(true);
        Block block=event.getClickedBlock(); Player player=event.getPlayer();
        if(event.getHand()!=EquipmentSlot.HAND || block==null) return;
        int index=event.getAction()==Action.LEFT_CLICK_BLOCK ? 0 : event.getAction()==Action.RIGHT_CLICK_BLOCK ? 1 : -1;
        if(index<0 || !admin(player)) return;
        selections.set(player.getUniqueId(),index,new Selections.Corner(block.getWorld().getUID(),block.getX(),block.getY(),block.getZ()));
        Selections.Selected selected=selections.selected(player.getUniqueId());
        Icons.tell(player,(index+1)+"点目："+block.getX()+", "+block.getY()+", "+block.getZ()
                +(selected==null ? "" : "　範囲 "+selected.region().sizeX()+"×"+selected.region().sizeY()+"×"+selected.region().sizeZ()));
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void wandBreak(BlockBreakEvent event) {
        // Creative instant-breaking must not destroy the clicked corner block.
        if(wand.is(event.getPlayer().getInventory().getItemInMainHand())) event.setCancelled(true);
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        selections.forget(event.getPlayer().getUniqueId()); editing.remove(event.getPlayer().getUniqueId());
    }

    // ---- Outlines (only to builders holding the wand) ----
    private void showOutlines() {
        for(Player player:Bukkit.getOnlinePlayers()) {
            if(!wand.is(player.getInventory().getItemInMainHand())) continue;
            UUID world=player.getWorld().getUID(),id=player.getUniqueId();
            for(int i=0;i<2;i++) {
                Selections.Corner c=selections.corner(id,i);
                if(c!=null && c.world().equals(world)) box(player,c.x(),c.y(),c.z(),c.x()+1,c.y()+1,c.z()+1,SELECTION);
            }
            Selections.Selected selected=selections.selected(id);
            if(selected!=null && selected.world().equals(world)) box(player,selected.region(),SELECTION);
            Course course=editing.containsKey(id) ? store.get(editing.get(id)) : null;
            if(course==null || !course.world().equals(world)) continue;
            if(course.region()!=null) box(player,course.region(),COURSE);
            Spot s=course.start();
            if(s!=null) for(double y=0;y<=2;y+=.25) dust(player,s.x(),s.y()+y,s.z(),START);
            for(BlockPos g:course.goals()) box(player,g.x()-.05,g.y()-.05,g.z()-.05,g.x()+1.05,g.y()+1.05,g.z()+1.05,GOAL);
            for(Trigger t:course.triggers()) { BlockPos p=t.pos(); box(player,p.x(),p.y(),p.z(),p.x()+1,p.y()+.5,p.z()+1,TRIGGER); }
        }
    }
    private void box(Player player,Region r,Particle.DustOptions color) {
        box(player,r.minX(),r.minY(),r.minZ(),r.maxX()+1,r.maxY()+1,r.maxZ()+1,color);
    }
    private void box(Player player,double x1,double y1,double z1,double x2,double y2,double z2,Particle.DustOptions color) {
        double[][] bounds={{x1,x2},{y1,y2},{z1,z2}};
        double step=Math.max(.5,Math.max(x2-x1,Math.max(y2-y1,z2-z1))/48);
        for(int axis=0;axis<3;axis++) for(int edge=0;edge<4;edge++) {
            int a=(axis+1)%3,b=(axis+2)%3; double[] p=new double[3];
            p[a]=bounds[a][edge&1]; p[b]=bounds[b][edge>>1];
            for(double t=bounds[axis][0];t<=bounds[axis][1]+1e-9;t+=step) { p[axis]=t; dust(player,p[0],p[1],p[2],color); }
        }
    }
    private static void dust(Player player,double x,double y,double z,Particle.DustOptions color) {
        player.spawnParticle(Particle.DUST,x,y,z,1,0,0,0,0,color,true);
    }

    private String authorName(Course course) {
        String name=Bukkit.getOfflinePlayer(course.author()).getName(); return name==null ? course.author().toString() : name;
    }
    static String worldName(Course course) {
        World world=Bukkit.getWorld(course.world()); return world==null ? "未読み込み ("+course.world()+")" : world.getName();
    }
    void failure(Player player,String message,Exception e) {
        Icons.tell(player,message); plugin.getLogger().log(Level.SEVERE,message,e);
    }
}
