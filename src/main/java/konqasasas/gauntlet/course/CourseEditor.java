package konqasasas.gauntlet.course;

import java.io.IOException;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import konqasasas.gauntlet.GroundGeometry;
import konqasasas.gauntlet.i18n.Msg;
import konqasasas.gauntlet.play.*;
import konqasasas.gauntlet.ui.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
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

/**
 * /course: GUI for creating and editing course definitions. Builders only.
 * Texts are the editor.* message keys (Japanese only for now).
 */
public final class CourseEditor implements Listener,CommandExecutor,TabCompleter {
    static final String ADMIN="gauntlet.admin";
    private static final Particle.DustOptions SELECTION=new Particle.DustOptions(Color.YELLOW,1f),
            COURSE=new Particle.DustOptions(Color.AQUA,1f),START=new Particle.DustOptions(Color.LIME,1.5f),
            GOAL=new Particle.DustOptions(Color.WHITE,.8f),TRIGGER=new Particle.DustOptions(Color.ORANGE,.8f),
            ROUTE=new Particle.DustOptions(Color.FUCHSIA,.7f),ROUTE_POINT=new Particle.DustOptions(Color.FUCHSIA,1.6f);
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
        if(!(sender instanceof Player player)) { Msg.send(sender,"settings.console"); return true; }
        if(args.length==0) { if(admin(player)) openList(player); }
        // Temporary entry points until the course selection feature exists.
        else if(args.length==2 && args[0].equalsIgnoreCase("play")) participants.enter(player,args[1].toLowerCase(Locale.ROOT),false);
        else if(args.length==2 && args[0].equalsIgnoreCase("practice")) participants.enter(player,args[1].toLowerCase(Locale.ROOT),true);
        else if(args.length==1 && args[0].equalsIgnoreCase("leave")) participants.leave(player);
        else if(args.length==1 && args[0].equalsIgnoreCase("undosave")) participants.undoSave(player);
        else Msg.send(player,player.hasPermission(ADMIN) ? "course.usage-admin" : "course.usage");
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        if(args.length==1) return List.of("play","practice","leave","undosave").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if(args.length==2 && (args[0].equalsIgnoreCase("play") || args[0].equalsIgnoreCase("practice"))) {
            boolean admin=sender.hasPermission(ADMIN);
            return store.all().stream().filter(c -> admin || c.status()==CourseStatus.DONE).map(Course::id)
                    .filter(id -> id.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
    boolean admin(Player player) {
        if(player.hasPermission(ADMIN)) return true;
        Msg.send(player,"editor.admin-only"); return false;
    }
    /** Editor messages are always sent with the [Gauntlet] prefix. */
    void tell(Player player,String key,TagResolver... args) { Msg.send(player,key,args); }
    static Component line(Player player,String key,TagResolver... args) { return Msg.text(player,key,args); }

    // ---- Course list ----
    void openList(Player player) {
        Menu menu=new Menu(6,Msg.plain(player,"editor.list.title"));
        List<Course> courses=store.all();
        for(int i=0;i<Math.min(courses.size(),45);i++) {
            Course course=courses.get(i);
            menu.button(i,courseIcon(player,course),e -> openEdit(player,course.id()));
        }
        List<Component> createLore=new ArrayList<>();
        if(courses.size()>45) createLore.add(line(player,"editor.list.more",Msg.arg("count",courses.size())));
        menu.button(49,Msg.icon(player,Material.WRITABLE_BOOK,"editor.list.create",createLore),e -> askNewId(player));
        menu.button(51,Msg.icon(player,Material.CHEST,"editor.templates-button"),e -> itemMenus.openTemplates(player,() -> openList(player)));
        menu.open(player);
    }
    private ItemStack courseIcon(Player player,Course course) {
        Material material=switch(course.status()) { case DRAFT -> Material.PAPER; case TESTING -> Material.MAP; case DONE -> Material.FILLED_MAP; };
        List<Component> lore=new ArrayList<>();
        lore.add(line(player,"editor.list.course.id",Msg.arg("id",course.id())));
        lore.add(line(player,"editor.list.course.status",Msg.arg("status",Msg.text(player,course.status().key()))));
        lore.add(line(player,"editor.list.course.author",Msg.arg("author",authorName(course))));
        warnings(player,course).forEach(p -> lore.add(line(player,"editor.warning",Msg.arg("text",p))));
        lore.add(line(player,"editor.list.course.click"));
        ItemStack icon=new ItemStack(material);
        icon.editMeta(meta -> { meta.displayName(Icons.text(course.name())); meta.lore(lore); });
        return icon;
    }
    private void askNewId(Player player) {
        prompts.ask(player,"editor.prompt.new-id",text -> {
            if(!admin(player)) return;
            String id=text.toLowerCase(Locale.ROOT);
            if(!CourseStore.validId(id)) { tell(player,"editor.new.invalid-id",Msg.arg("text",text)); return; }
            Course course=Course.create(id,player.getUniqueId(),player.getWorld().getUID());
            Selections.Selected selected=selections.selected(player.getUniqueId());
            if(selected!=null) course=course.withRegion(selected.world(),selected.region());
            try { store.create(course); }
            catch(IOException e) { failure(player,"editor.new.failed",e,Msg.arg("id",id)); return; }
            tell(player,"editor.new.done",Msg.arg("id",id));
            openEdit(player,id);
        });
    }
    /** Course problems plus references to deleted templates, as text for this player. */
    List<String> warnings(Player player,Course course) {
        List<String> warnings=new ArrayList<>(course.problems().stream().map(k -> Msg.plain(player,k)).toList());
        boolean missing=course.loadout().stream().anyMatch(r -> r!=null && !items.exists(r))
                || course.triggers().stream().flatMap(t -> t.actions().stream()).anyMatch(a -> a.item()!=null && !items.exists(a.item()));
        if(missing) warnings.add(Msg.plain(player,"editor.warn.uses-deleted-template"));
        return warnings;
    }

    // ---- Course editing ----
    void openEdit(Player player,String id) {
        Course course=store.get(id);
        if(course==null) { tell(player,"editor.deleted",Msg.arg("id",id)); openList(player); return; }
        editing.put(player.getUniqueId(),id);
        Menu menu=new Menu(4,Msg.plain(player,"editor.edit.title",Msg.arg("name",course.name())));
        List<Component> info=new ArrayList<>(List.of(
                line(player,"editor.list.course.id",Msg.arg("id",course.id())),
                line(player,"editor.list.course.author",Msg.arg("author",authorName(course))),
                line(player,"editor.edit.world",Msg.arg("world",worldName(player,course))),
                line(player,"editor.edit.rev",Msg.arg("rev",course.rev()))));
        if(course.problems().isEmpty()) info.add(line(player,"editor.edit.playable"));
        warnings(player,course).forEach(p -> info.add(line(player,"editor.warning",Msg.arg("text",p))));
        ItemStack book=new ItemStack(Material.BOOK);
        book.editMeta(meta -> { meta.displayName(Icons.text(course.name())); meta.lore(info); });
        menu.label(4,book);
        menu.button(10,Msg.icon(player,Material.NAME_TAG,"editor.edit.name",Msg.arg("current",course.name())),
                e -> prompts.ask(player,"editor.prompt.name",text -> {
                    if(text.isBlank() || text.length()>Course.MAX_NAME_LENGTH) { tell(player,"editor.name-length",Msg.arg("max",Course.MAX_NAME_LENGTH)); return; }
                    update(player,id,c -> c.withName(text));
                },Msg.arg("max",Course.MAX_NAME_LENGTH)));
        menu.button(11,Msg.icon(player,Material.MAP,"editor.edit.region",
                Msg.arg("current",course.region()==null ? Msg.text(player,"editor.unset") : Component.text(course.region().describe()))),
                e -> setRegion(player,id));
        menu.button(12,Msg.icon(player,Material.RECOVERY_COMPASS,"editor.edit.start",
                Msg.arg("current",course.start()==null ? Msg.text(player,"editor.unset") : Component.text(course.start().describe()))),
                e -> setStart(player,id));
        menu.button(13,Msg.icon(player,Material.CLOCK,"editor.edit.time",
                Msg.arg("current",course.timeLimitSeconds()==0 ? Msg.text(player,"editor.none") : Msg.text(player,"time.seconds",Msg.arg("value",course.timeLimitSeconds())))),
                e -> adjustTime(player,id,e));
        menu.button(14,Msg.icon(player,statusIcon(course.status()),"editor.edit.status",Msg.arg("status",Msg.text(player,course.status().key()))),
                e -> update(player,id,c -> c.withStatus(c.status().next())));
        menu.button(15,Msg.icon(player,Material.ENDER_PEARL,"editor.edit.go-start"),e -> teleportToStart(player,id));
        menu.button(16,Msg.icon(player,Material.BREEZE_ROD,"editor.edit.wand"),e -> giveWand(player));
        List<Component> goals=new ArrayList<>();
        course.goals().stream().limit(5).forEach(g -> goals.add(Component.text("・"+g.describe())));
        menu.button(19,Msg.icon(player,Material.DIAMOND_BLOCK,"editor.edit.goal",goals,Msg.arg("count",course.goals().size())),e -> toggleGoal(player,id));
        menu.button(20,Msg.icon(player,Material.HEAVY_WEIGHTED_PRESSURE_PLATE,"editor.edit.trigger-here"),e -> triggerMenus.openAtFeet(player,id));
        menu.button(21,Msg.icon(player,Material.REPEATER,"editor.edit.triggers",Msg.arg("count",course.triggers().size())),e -> triggerMenus.openList(player,id));
        List<Component> loadout=new ArrayList<>();
        for(int i=0;i<Course.COURSE_SLOTS;i++) loadout.add(line(player,"editor.edit.loadout-slot",Msg.arg("slot",i+1),Msg.arg("item",items.name(player,course.loadout().get(i)))));
        menu.button(22,Msg.icon(player,Material.ARMOR_STAND,"editor.edit.loadout",loadout),e -> itemMenus.openLoadout(player,id));
        menu.button(23,Msg.icon(player,course.failLava() ? Material.LAVA_BUCKET : Material.BUCKET,"editor.edit.fail-lava",Msg.arg("state",onOff(player,course.failLava()))),
                e -> update(player,id,c -> c.edit().failLava(!c.failLava()).build()));
        menu.button(24,Msg.icon(player,course.failOutside() ? Material.STRUCTURE_VOID : Material.GLASS,"editor.edit.fail-outside",Msg.arg("state",onOff(player,course.failOutside()))),
                e -> update(player,id,c -> c.edit().failOutside(!c.failOutside()).build()));
        menu.button(25,Msg.icon(player,Material.CHEST,"editor.templates-button"),e -> itemMenus.openTemplates(player,() -> openEdit(player,id)));
        menu.button(27,Msg.icon(player,Material.ARROW,"editor.back-to-list"),e -> openList(player));
        menu.button(28,Msg.icon(player,Material.LEAD,"editor.edit.route",Msg.arg("count",course.route().size()),
                Msg.arg("length",String.format(Locale.ROOT,"%.1f",new RouteProgress(course.route()).total()))),e -> editRoute(player,id,e));
        menu.button(29,Msg.icon(player,Material.EXPERIENCE_BOTTLE,"editor.edit.rank",
                Msg.arg("current",course.rank()==null ? Msg.text(player,"editor.unset") : Component.text(course.rank()))),e -> adjustRank(player,id,e));
        menu.button(30,Msg.icon(player,Material.OAK_SIGN,"editor.edit.stage",
                Msg.arg("current",course.stage()==null ? Msg.text(player,"editor.unset") : Component.text(course.stage()==0 ? "BOSS" : String.valueOf(course.stage())))),
                e -> adjustStage(player,id,e));
        menu.button(31,Msg.icon(player,Material.NETHER_STAR,course.problems().isEmpty() ? "editor.edit.play" : "editor.edit.play-blocked"),
                e -> { if(admin(player)) participants.enter(player,id,e.isRightClick()); });
        menu.button(35,Msg.icon(player,Material.BARRIER,"editor.edit.delete"),e -> openDelete(player,id));
        menu.open(player);
    }
    private static Component onOff(Player player,boolean on) { return Msg.text(player,on ? "setting.value.on" : "setting.value.off"); }
    private static Material statusIcon(CourseStatus status) {
        return switch(status) { case DRAFT -> Material.GRAY_DYE; case TESTING -> Material.YELLOW_DYE; case DONE -> Material.LIME_DYE; };
    }
    /**
     * Saves a change; false (already reported) when the course is gone or the save failed.
     * An edit that changes how the course plays gets a new revision (records are per revision).
     */
    boolean save(Player player,String id,UnaryOperator<Course> change) {
        if(!admin(player)) return false;
        Course course=store.get(id);
        if(course==null) { tell(player,"editor.deleted",Msg.arg("id",id)); openList(player); return false; }
        try {
            Course next=change.apply(course);
            if(!next.sameGameplay(course)) next=next.edit().rev(course.rev()+1).build();
            store.save(next); return true;
        }
        catch(IOException|RuntimeException e) { failure(player,"editor.save-failed",e,Msg.arg("id",id)); return false; }
    }
    private void update(Player player,String id,UnaryOperator<Course> change) {
        if(save(player,id,change)) openEdit(player,id);
    }
    private void setRegion(Player player,String id) {
        Selections.Selected selected=selections.selected(player.getUniqueId());
        if(selected==null) { tell(player,"editor.region.no-selection"); return; }
        update(player,id,c -> c.withRegion(selected.world(),selected.region()));
        tell(player,"editor.region.done",Msg.arg("region",selected.region().describe()));
    }
    private void setStart(Player player,String id) {
        Course course=store.get(id); if(course==null) return;
        Location at=player.getLocation();
        if(!at.getWorld().getUID().equals(course.world())) { tell(player,"editor.same-world",Msg.arg("world",worldName(player,course))); return; }
        if(course.region()==null) { tell(player,"editor.start.no-region"); return; }
        Spot spot=new Spot(at.getX(),at.getY(),at.getZ(),at.getYaw(),at.getPitch());
        if(!course.contains(spot)) { tell(player,"editor.start.outside"); return; }
        if(!player.isOnGround()) { tell(player,"editor.start.not-ground"); return; }
        update(player,id,c -> c.withStart(spot));
        tell(player,"editor.start.done",Msg.arg("spot",spot.describe()));
    }
    private void toggleGoal(Player player,String id) {
        Course course=store.get(id); if(course==null) return;
        if(!player.getWorld().getUID().equals(course.world())) { tell(player,"editor.same-world",Msg.arg("world",worldName(player,course))); return; }
        if(!player.isOnGround()) { tell(player,"editor.goal.not-ground"); return; }
        Location at=player.getLocation();
        Block goal=GroundGeometry.supporting(at.getWorld(),player.getBoundingBox(),at.getY()).stream()
                .filter(b -> b.getType()==Material.DIAMOND_BLOCK).findFirst().orElse(null);
        if(goal==null) { tell(player,"editor.goal.not-diamond"); return; }
        if(course.region()==null || !course.region().contains(goal.getX(),goal.getY(),goal.getZ())) { tell(player,"editor.goal.outside"); return; }
        BlockPos pos=new BlockPos(goal.getX(),goal.getY(),goal.getZ());
        boolean removing=course.goals().contains(pos);
        update(player,id,c -> c.toggleGoal(pos));
        tell(player,removing ? "editor.goal.removed" : "editor.goal.added",Msg.arg("pos",pos.describe()));
    }
    private void adjustTime(Player player,String id,InventoryClickEvent event) {
        int delta=(event.isShiftClick() ? 10 : 1)*(event.isRightClick() ? -1 : 1);
        update(player,id,c -> c.withTimeLimit(c.timeLimitSeconds()+delta));
    }
    /** Left: add the current position, right: remove the last point, Shift+right: clear. */
    private void editRoute(Player player,String id,InventoryClickEvent event) {
        Course course=store.get(id); if(course==null) return;
        if(event.isRightClick() && event.isShiftClick()) { update(player,id,c -> c.edit().clearRoute().build()); tell(player,"editor.route.cleared"); return; }
        if(event.isRightClick()) {
            if(course.route().isEmpty()) { tell(player,"editor.route.empty"); return; }
            update(player,id,c -> c.edit().removeLastRoutePoint().build()); tell(player,"editor.route.removed"); return;
        }
        if(!player.getWorld().getUID().equals(course.world())) { tell(player,"editor.same-world",Msg.arg("world",worldName(player,course))); return; }
        if(course.route().size()>=Course.MAX_ROUTE_POINTS) { tell(player,"editor.route.full",Msg.arg("max",Course.MAX_ROUTE_POINTS)); return; }
        Location at=player.getLocation();
        // Body center, so the route runs where the player actually is.
        RoutePoint point=new RoutePoint(at.getX(),at.getY()+.9,at.getZ());
        update(player,id,c -> c.edit().addRoutePoint(point).build());
        tell(player,"editor.route.added",Msg.arg("index",course.route().size()+1),Msg.arg("point",point.describe()));
    }
    /** Left: +1, right: -1, Shift: clear. Temporary manual numbering until ranks exist. */
    private void adjustRank(Player player,String id,InventoryClickEvent event) {
        update(player,id,c -> {
            if(event.isShiftClick()) return c.edit().rank(null).build();
            int current=c.rank()==null ? 0 : c.rank();
            int next=Math.clamp(current+(event.isRightClick() ? -1 : 1),1,Course.MAX_RANK);
            return c.edit().rank(next).build();
        });
    }
    /** Left: +1, right: -1 (0 = BOSS), Shift: clear. */
    private void adjustStage(Player player,String id,InventoryClickEvent event) {
        update(player,id,c -> {
            if(event.isShiftClick()) return c.edit().stage(null).build();
            int current=c.stage()==null ? (event.isRightClick() ? 1 : 0) : c.stage();
            int next=Math.clamp(current+(event.isRightClick() ? -1 : 1),0,Course.MAX_STAGE);
            return c.edit().stage(next).build();
        });
    }
    private void teleportToStart(Player player,String id) {
        Course course=store.get(id); if(course==null) return;
        if(course.start()==null) { tell(player,"editor.start.unset"); return; }
        Spot s=course.start();
        teleport(player,course,s.x(),s.y(),s.z(),s.yaw(),s.pitch());
    }
    void teleport(Player player,Course course,double x,double y,double z,float yaw,float pitch) {
        World world=Bukkit.getWorld(course.world());
        if(world==null) { tell(player,"editor.world-unloaded"); return; }
        player.closeInventory();
        if(!Teleports.to(player,new Location(world,x,y,z,yaw,pitch))) tell(player,"editor.teleport-denied");
    }
    private void giveWand(Player player) {
        if(!admin(player)) return;
        for(ItemStack item:player.getInventory().getContents()) if(wand.is(item)) { tell(player,"editor.wand.already"); return; }
        if(!player.getInventory().addItem(wand.create(player)).isEmpty()) tell(player,"editor.kit.full");
        else tell(player,"editor.wand.given");
    }
    private void openDelete(Player player,String id) {
        Menu menu=new Menu(3,Msg.plain(player,"editor.delete.title",Msg.arg("id",id)));
        menu.button(11,Msg.icon(player,Material.RED_CONCRETE,"editor.delete.confirm",Msg.arg("id",id)),e -> {
            if(!admin(player)) return;
            try { store.delete(id); tell(player,"editor.delete.done",Msg.arg("id",id)); editing.values().removeIf(id::equals); }
            catch(IOException e2) { failure(player,"editor.delete.failed",e2,Msg.arg("id",id)); }
            openList(player);
        });
        menu.button(15,Msg.icon(player,Material.LIME_CONCRETE,"editor.delete.cancel"),e -> openEdit(player,id));
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
        tell(player,"editor.wand.corner",Msg.arg("index",index+1),Msg.arg("pos",block.getX()+", "+block.getY()+", "+block.getZ()),
                Msg.arg("size",selected==null ? "" : "  "+selected.region().sizeX()+"×"+selected.region().sizeY()+"×"+selected.region().sizeZ()));
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
            route(player,course.route());
        }
    }
    private void route(Player player,List<RoutePoint> route) {
        for(int i=0;i<route.size();i++) {
            RoutePoint p=route.get(i);
            dust(player,p.x(),p.y(),p.z(),ROUTE_POINT);
            if(i==0) continue;
            RoutePoint a=route.get(i-1);
            double length=Math.sqrt((p.x()-a.x())*(p.x()-a.x())+(p.y()-a.y())*(p.y()-a.y())+(p.z()-a.z())*(p.z()-a.z()));
            int steps=(int)Math.min(80,Math.ceil(length/.5));
            for(int k=1;k<steps;k++) {
                double t=k/(double)steps;
                dust(player,a.x()+(p.x()-a.x())*t,a.y()+(p.y()-a.y())*t,a.z()+(p.z()-a.z())*t,ROUTE);
            }
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
    static String worldName(Player player,Course course) {
        World world=Bukkit.getWorld(course.world());
        return world==null ? Msg.plain(player,"editor.world-not-loaded",Msg.arg("id",course.world())) : world.getName();
    }
    void failure(Player player,String key,Exception e,TagResolver... args) {
        tell(player,key,args); plugin.getLogger().log(Level.SEVERE,Msg.plain(player,key,args),e);
    }
}
