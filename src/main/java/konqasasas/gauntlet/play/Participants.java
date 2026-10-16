package konqasasas.gauntlet.play;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import io.papermc.paper.event.packet.ClientTickEndEvent;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import konqasasas.gauntlet.course.*;
import konqasasas.gauntlet.GroundGeometry;
import konqasasas.gauntlet.ui.Icons;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * Participant mode. Builders and players play the same thing, either the
 * official run (recorded, back to the start on failure) or practice (one save
 * state, optional flight, segment times, never recorded).
 *
 * Hotbar: 1-4 course items (Q on 1 = back to start / save point), 5 switch
 * official/practice, 6 flight (practice), 7 save (practice), 8 retry and 9 leave
 * after reaching the goal.
 */
public final class Participants implements Listener {
    private static final String ADMIN="gauntlet.admin";
    private static final double MOVE_EPSILON_SQUARED=1e-8;
    /** Server ticks without a client tick end before the run is marked unmeasurable. */
    private static final int SILENCE_LIMIT=20;
    /** Server-clock cap on the time limit, for clients that stop reporting ticks. */
    private static final double WALL_CLOCK_MARGIN=1.5;
    private static final int MODE_SLOT=4,FLIGHT_SLOT=5,SAVE_SLOT=6,RETRY_SLOT=7,LEAVE_SLOT=8;
    private enum SystemItem { MODE,FLIGHT,SAVE,RETRY,LEAVE }
    private record Save(Location at,ItemStack[] contents,List<PotionEffect> effects,Set<BlockPos> fired,Set<Object> scaffold,
                        Map<String,List<CourseMobs.Saved>> mobs) {}
    private static final class Session {
        final String course; boolean practice;
        final RunClock clock=new RunClock();
        int lastClientTick; long startNanos; boolean unmeasured,goalReached,flew;
        String pendingFail;
        final Set<BlockPos> fired=new HashSet<>();
        final Map<String,List<UUID>> mobs=new HashMap<>();
        Save save;
        Session(String course,boolean practice) { this.course=course; this.practice=practice; }
    }
    private final JavaPlugin plugin;
    private final CourseStore courses;
    private final RecordStore records;
    private final ReturnStore returns;
    private final Scaffold scaffold;
    private final CourseItems items;
    private final CourseMobs mobs=new CourseMobs();
    private final NamespacedKey itemKey;
    private final Map<UUID,Session> sessions=new HashMap<>();

    public Participants(JavaPlugin plugin,CourseStore courses,RecordStore records,ReturnStore returns,Scaffold scaffold,CourseItems items) {
        this.plugin=plugin; this.courses=courses; this.records=records; this.returns=returns; this.scaffold=scaffold; this.items=items;
        itemKey=new NamespacedKey(plugin,"play");
    }
    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        plugin.getServer().getPluginManager().registerEvents(mobs,plugin);
        mobs.sweep();
        if(records.invalidLines()>0) plugin.getLogger().warning("Skipped "+records.invalidLines()+" unreadable lines in records.jsonl");
        // After a crash or reload, give back what online players had before their run.
        for(Player player:Bukkit.getOnlinePlayers()) if(returns.get(player.getUniqueId())!=null) restore(player);
    }
    public void stop() {
        for(UUID id:List.copyOf(sessions.keySet())) {
            Player player=Bukkit.getPlayer(id);
            if(player!=null) end(player);
        }
        sessions.clear();
    }
    public boolean active(Player player) { return sessions.containsKey(player.getUniqueId()); }

    // ---- Entering and leaving ----
    public void enter(Player player,String id,boolean practice) {
        Course course=courses.get(id);
        if(course==null) { Icons.tell(player,"コース "+id+" が見つかりません。"); return; }
        if(!course.problems().isEmpty()) { Icons.tell(player,"コース "+id+" は未設定の項目があります："+String.join("、",course.problems())); return; }
        // Until progression exists, "unlocked" means published.
        if(!player.hasPermission(ADMIN) && course.status()!=CourseStatus.DONE) { Icons.tell(player,"このコースはまだ公開されていません。"); return; }
        if(Bukkit.getWorld(course.world())==null) { Icons.tell(player,"コースのワールドが読み込まれていません。"); return; }
        UUID uuid=player.getUniqueId();
        Session previous=sessions.remove(uuid);
        if(previous!=null) { record(player,previous,RunRecord.Result.RETIRE); mobs.remove(allMobs(previous)); scaffold.clear(player); }
        // Keep an older entry: it holds the inventory from before any participant run.
        else if(returns.get(uuid)==null) {
            try { returns.put(uuid,capture(player)); }
            catch(IOException|RuntimeException e) { failure(player,"所持品を保存できないため、参加者モードに入れませんでした。",e); return; }
        }
        Session session=new Session(id,practice); sessions.put(uuid,session);
        player.closeInventory();
        player.setAllowFlight(false);
        if(!toStart(player,course)) { end(player); return; }
        reset(player,session,course);
        Icons.tell(player,"「"+course.name()+"」"+(practice ? "練習" : "本番")+"開始。1番でQ："+(practice ? "セーブ地点" : "スタート")+"へ戻る　5番：本番／練習の切り替え　終了：/course leave");
    }
    public void leave(Player player) {
        if(sessions.containsKey(player.getUniqueId())) { end(player); Icons.tell(player,"参加者モードを終了しました。"); }
        else if(returns.get(player.getUniqueId())!=null) { restore(player); Icons.tell(player,"保存していた所持品と位置に戻しました。"); }
        else Icons.tell(player,"参加者モードではありません。");
    }
    private void end(Player player) {
        Session session=sessions.remove(player.getUniqueId());
        if(session!=null) { record(player,session,RunRecord.Result.RETIRE); mobs.remove(allMobs(session)); }
        scaffold.clear(player); stopMotion(player);
        for(PotionEffect effect:player.getActivePotionEffects()) player.removePotionEffect(effect.getType());
        player.sendActionBar(Component.empty());
        restore(player);
    }
    private ReturnStore.Saved capture(Player player) {
        Location at=player.getLocation();
        ItemStack[] contents=Arrays.stream(player.getInventory().getContents()).map(i -> i==null ? ItemStack.empty() : i).toArray(ItemStack[]::new);
        return new ReturnStore.Saved(at.getWorld().getUID(),at.getX(),at.getY(),at.getZ(),at.getYaw(),at.getPitch(),
                player.getGameMode().name(),player.getAllowFlight(),Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(contents)));
    }
    private void restore(Player player) {
        ReturnStore.Saved saved=returns.get(player.getUniqueId()); if(saved==null) return;
        try {
            player.getInventory().setContents(ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(saved.inventory())));
            player.setGameMode(GameMode.valueOf(saved.gameMode()));
            player.setAllowFlight(saved.allowFlight() || player.getGameMode()==GameMode.CREATIVE || player.getGameMode()==GameMode.SPECTATOR);
            World world=Bukkit.getWorld(saved.world());
            Location target=world==null ? Bukkit.getWorlds().getFirst().getSpawnLocation() : new Location(world,saved.x(),saved.y(),saved.z(),saved.yaw(),saved.pitch());
            player.teleport(target,PlayerTeleportEvent.TeleportCause.PLUGIN);
            returns.remove(player.getUniqueId());
        } catch(IOException|RuntimeException e) { failure(player,"所持品の復元が完了していません。/course leave で再試行できます。",e); }
    }

    // ---- Start state, failure, practice save/load ----
    private boolean toStart(Player player,Course course) {
        Spot s=course.start(); World world=Bukkit.getWorld(course.world());
        return world!=null && teleport(player,new Location(world,s.x(),s.y(),s.z(),s.yaw(),s.pitch()));
    }
    private boolean teleport(Player player,Location target) {
        World world=target.getWorld();
        if(!world.isChunkLoaded(target.getBlockX()>>4,target.getBlockZ()>>4) && !world.loadChunk(target.getBlockX()>>4,target.getBlockZ()>>4,false)) {
            Icons.tell(player,"移動先のチャンクを読み込めませんでした。"); return false;
        }
        if(!player.teleport(target,PlayerTeleportEvent.TeleportCause.PLUGIN)) { Icons.tell(player,"移動が拒否されました。"); return false; }
        return true;
    }
    /** The start state: no scaffold, no mobs, fresh loadout, clock waiting. Flight permission stays in practice. */
    private void reset(Player player,Session session,Course course) {
        stopMotion(player); player.setFireTicks(0);
        scaffold.clear(player);
        mobs.remove(allMobs(session)); session.mobs.clear(); session.fired.clear();
        if(player.getGameMode()!=GameMode.ADVENTURE) player.setGameMode(GameMode.ADVENTURE);
        if(!session.practice) player.setAllowFlight(false);
        heal(player);
        for(PotionEffect effect:player.getActivePotionEffects()) player.removePotionEffect(effect.getType());
        PlayerInventory inventory=player.getInventory();
        inventory.clear();
        for(int slot=0;slot<Course.COURSE_SLOTS;slot++) {
            ItemRef ref=course.loadout().get(slot);
            if(ref!=null) inventory.setItem(slot,items.create(ref));
        }
        inventory.setHeldItemSlot(0);
        restartClock(session);
        systemItems(player,session);
    }
    private void restartClock(Session session) {
        session.clock.reset(); session.unmeasured=false; session.goalReached=false; session.flew=false;
        session.pendingFail=null; session.lastClientTick=Bukkit.getCurrentTick();
    }
    /** Q on slot 1, retry item, failure: back to the start (official) or the save point (practice). */
    private void restart(Player player,Session session) {
        Course course=courses.get(session.course);
        if(course==null || !course.problems().isEmpty()) { end(player); Icons.tell(player,"コースが変更されたため終了しました。"); return; }
        record(player,session,RunRecord.Result.RETIRE);
        if(session.practice && session.save!=null) load(player,session);
        else if(toStart(player,course)) reset(player,session,course);
    }
    private void fail(Player player,Session session,Course course,String reason) {
        record(player,session,RunRecord.Result.FAIL);
        player.showTitle(Title.title(Component.empty(),Component.text("失敗："+reason,NamedTextColor.RED),
                Title.Times.times(java.time.Duration.ZERO,java.time.Duration.ofMillis(800),java.time.Duration.ofMillis(200))));
        player.playSound(player.getLocation(),Sound.ENTITY_VILLAGER_NO,SoundCategory.PLAYERS,.7f,1f);
        if(session.practice && session.save!=null) load(player,session);
        else if(toStart(player,course)) reset(player,session,course);
    }
    private void save(Player player,Session session) {
        if(!session.practice) return;
        if(session.goalReached) { Icons.tell(player,"ゴール後はセーブできません。"); return; }
        Location at=player.getLocation();
        if(!player.isOnGround() || player.isFlying() || GroundGeometry.supporting(at.getWorld(),player.getBoundingBox(),at.getY()).isEmpty()) {
            Icons.tell(player,"地面の上でセーブしてください。"); return;
        }
        // Whole inventory, so an equipped elytra (armor slot) comes back too.
        ItemStack[] contents=Arrays.stream(player.getInventory().getContents()).map(i -> i==null ? null : i.clone()).toArray(ItemStack[]::new);
        Map<String,List<CourseMobs.Saved>> savedMobs=new HashMap<>();
        session.mobs.forEach((key,ids) -> savedMobs.put(key,mobs.snapshot(ids)));
        session.save=new Save(at.clone(),contents,List.copyOf(player.getActivePotionEffects()),Set.copyOf(session.fired),scaffold.placed(player),savedMobs);
        player.playSound(at,Sound.ENTITY_EXPERIENCE_ORB_PICKUP,SoundCategory.PLAYERS,1f,1f);
        player.sendActionBar(Component.text("セーブしました",NamedTextColor.AQUA));
    }
    private void load(Player player,Session session) {
        Save save=session.save;
        if(!teleport(player,save.at().clone())) return;
        stopMotion(player); player.setFireTicks(0); heal(player);
        // Scaffold placed before saving stays; anything placed afterwards goes.
        scaffold.clearExcept(player,save.scaffold());
        // Replaces everything, including any stray item returned by a cancelled drop.
        player.getInventory().setContents(Arrays.stream(save.contents()).map(i -> i==null ? null : i.clone()).toArray(ItemStack[]::new));
        for(PotionEffect effect:player.getActivePotionEffects()) player.removePotionEffect(effect.getType());
        player.addPotionEffects(save.effects());
        session.fired.clear(); session.fired.addAll(save.fired());
        mobs.remove(allMobs(session)); session.mobs.clear();
        save.mobs().forEach((key,saved) -> session.mobs.put(key,mobs.respawn(saved)));
        player.getInventory().setHeldItemSlot(0);
        restartClock(session);
        systemItems(player,session);
    }
    private void switchMode(Player player,Session session) {
        Course course=courses.get(session.course); if(course==null) { end(player); return; }
        record(player,session,RunRecord.Result.RETIRE);
        session.practice=!session.practice; session.save=null;
        if(toStart(player,course)) reset(player,session,course);
        Icons.tell(player,session.practice ? "練習モードにしました。7番：セーブ　1番でQ：セーブ地点へ　6番：飛行" : "本番にしました。記録が残ります。");
    }
    private void toggleFlight(Player player,Session session) {
        if(!session.practice) return;
        boolean allow=!player.getAllowFlight();
        player.setAllowFlight(allow);
        if(!allow && player.isFlying()) player.setFlying(false);
        systemItems(player,session);
        player.sendActionBar(Component.text(allow ? "飛行：オン（ジャンプ2回で飛ぶ。飛んだ区間のタイムは無効）" : "飛行：オフ",NamedTextColor.AQUA));
    }
    /** Logs a running official attempt. Waiting/finished/practice/unmeasured attempts are not logged. */
    private void record(Player player,Session session,RunRecord.Result result) {
        if(session.practice || session.clock.phase()!=RunClock.Phase.RUNNING || session.unmeasured) return;
        Location at=player.getLocation();
        try { records.append(new RunRecord(session.course,player.getUniqueId(),result,session.clock.elapsed(),at.getX(),at.getY(),at.getZ(),System.currentTimeMillis())); }
        catch(IOException|RuntimeException e) { plugin.getLogger().log(Level.SEVERE,"Cannot write run record",e); }
        session.clock.reset();
    }
    private void goal(Player player,Session session,Course course) {
        boolean timed=session.clock.phase()==RunClock.Phase.RUNNING;
        session.clock.stop(); session.goalReached=true;
        long ticks=session.clock.elapsed(); String time=RunClock.seconds(ticks)+"秒";
        if(session.practice) {
            String segment=!timed ? "" : session.flew || session.unmeasured ? "区間タイム：無効（飛行または計測不能）" : "区間タイム "+time+"（"+ticks+" tick）";
            player.showTitle(Title.title(Component.text("ゴール（練習）",NamedTextColor.AQUA),Component.text(segment)));
            if(!segment.isEmpty()) Icons.tell(player,"練習："+segment);
        } else if(session.unmeasured) {
            player.showTitle(Title.title(Component.text("ゴール"),Component.text("計測不能のため記録なし",NamedTextColor.RED)));
        } else {
            OptionalLong before=records.best(course.id(),player.getUniqueId());
            Location at=player.getLocation();
            try { records.append(new RunRecord(course.id(),player.getUniqueId(),RunRecord.Result.CLEAR,ticks,at.getX(),at.getY(),at.getZ(),System.currentTimeMillis())); }
            catch(IOException|RuntimeException e) { failure(player,"記録を保存できませんでした。",e); }
            String best;
            if(before.isEmpty()) best="初クリア！";
            else if(ticks<before.getAsLong()) best="自己ベスト更新！（-"+RunClock.seconds(before.getAsLong()-ticks)+"秒）";
            else best="自己ベスト "+RunClock.seconds(before.getAsLong())+"秒（+"+RunClock.seconds(ticks-before.getAsLong())+"秒）";
            player.showTitle(Title.title(Component.text("クリア！",NamedTextColor.GOLD),Component.text(time)));
            Icons.tell(player,"「"+course.name()+"」タイム "+time+"（"+ticks+" tick）　"+best);
        }
        player.playSound(player.getLocation(),Sound.UI_TOAST_CHALLENGE_COMPLETE,SoundCategory.PLAYERS,1f,1f);
        systemItems(player,session);
    }
    /** End of a practice segment at a trigger. */
    private void segment(Player player,Session session) {
        session.clock.stop();
        long ticks=session.clock.elapsed();
        String text=session.flew || session.unmeasured ? "区間タイム：無効（飛行または計測不能）" : "区間タイム "+RunClock.seconds(ticks)+"秒（"+ticks+" tick）";
        Icons.tell(player,"練習："+text);
    }

    // ---- Triggers ----
    private void fire(Player player,Session session,Course course,Trigger trigger) {
        session.fired.add(trigger.pos());
        if(session.practice && session.clock.phase()==RunClock.Phase.RUNNING) segment(player,session);
        for(int i=0;i<trigger.actions().size();i++) {
            TriggerAction action=trigger.actions().get(i);
            try { run(player,session,course,trigger.pos().describe()+"#"+i,action); }
            catch(RuntimeException e) { plugin.getLogger().log(Level.SEVERE,"Trigger action failed at "+trigger.pos().describe()+" in "+course.id(),e); }
        }
    }
    private void run(Player player,Session session,Course course,String key,TriggerAction action) {
        PlayerInventory inventory=player.getInventory();
        switch(action.type()) {
            case GIVE -> {
                ItemStack item=items.create(action.item());
                if(item==null) { builderWarning(player,"テンプレートが削除されているためアイテムを渡せません。"); return; }
                for(int slot=0;slot<inventory.getSize();slot++) if(action.item().equals(items.ref(inventory.getItem(slot)))) return;
                int slot=action.placement().slot();
                if(slot<0) for(int i=1;i<Course.COURSE_SLOTS && slot<0;i++) { ItemStack there=inventory.getItem(i); if(there==null || there.getType().isAir()) slot=i; }
                if(slot<0) { builderWarning(player,"2〜4番に空きがないためアイテムを渡せませんでした。"); return; }
                inventory.setItem(slot,item);
            }
            case REMOVE -> {
                for(int slot=0;slot<inventory.getSize();slot++) {
                    ItemRef ref=items.ref(inventory.getItem(slot));
                    if(ref!=null && ref.template().equals(action.item().template()) && (action.allLevels() || ref.level()==action.item().level())) inventory.setItem(slot,null);
                }
            }
            case MOB -> {
                List<UUID> alive=session.mobs.getOrDefault(key,List.of());
                if(mobs.anyAlive(alive)) return;
                World world=Bukkit.getWorld(course.world()); MobSpec spec=MobSpec.parse(action.mob());
                if(world==null || spec==null) return;
                List<UUID> spawned=mobs.spawn(world,action.mobAt(),spec);
                if(spawned.isEmpty()) builderWarning(player,"mobを出せませんでした。内容を確認してください："+action.mob());
                session.mobs.put(key,spawned);
            }
            case SOUND -> player.playSound(player.getLocation(),action.sound(),SoundCategory.PLAYERS,action.volume(),action.pitch());
        }
    }
    private static void builderWarning(Player player,String message) { if(player.hasPermission(ADMIN)) Icons.tell(player,"[制作者向け] "+message); }

    // ---- Per client tick ----
    @EventHandler public void clientTick(ClientTickEndEvent event) {
        Player player=event.getPlayer(); Session session=sessions.get(player.getUniqueId());
        if(session==null) return;
        session.lastClientTick=Bukkit.getCurrentTick();
        Course course=courses.get(session.course);
        if(course==null || !course.problems().isEmpty()) { end(player); Icons.tell(player,"コースが削除・変更されたため終了しました。"); return; }
        if(player.isDead()) return;
        Location at=player.getLocation(); boolean sameWorld=at.getWorld().getUID().equals(course.world());
        if(!session.goalReached) {
            if(!sameWorld || (course.failOutside() && !course.region().contains(at.getX(),at.getY(),at.getZ()))) { fail(player,session,course,"範囲外"); return; }
            if(course.failLava() && player.isInLava()) { fail(player,session,course,"マグマ"); return; }
        }
        boolean moved=false;
        if(session.clock.phase()==RunClock.Phase.WAITING) {
            Spot s=course.start();
            moved=session.save==null || !session.practice
                    ? at.toVector().distanceSquared(new Vector(s.x(),s.y(),s.z()))>MOVE_EPSILON_SQUARED
                    : at.toVector().distanceSquared(session.save.at().toVector())>MOVE_EPSILON_SQUARED;
        }
        if(session.clock.tick(moved)) session.startNanos=System.nanoTime();
        if(player.isFlying()) session.flew=true;
        if(session.clock.phase()!=RunClock.Phase.WAITING && !session.goalReached) {
            for(BlockPos pos:cellsAtFeet(player.getBoundingBox())) {
                Trigger trigger=course.trigger(pos);
                if(trigger!=null && !session.fired.contains(pos)) fire(player,session,course,trigger);
            }
            if(player.isOnGround()) for(Block block:GroundGeometry.supporting(at.getWorld(),player.getBoundingBox(),at.getY()))
                if(course.isGoal(block.getX(),block.getY(),block.getZ())) { goal(player,session,course); break; }
            if(!session.goalReached && !session.practice && session.clock.phase()==RunClock.Phase.RUNNING
                    && course.timeLimitSeconds()>0 && session.clock.elapsed()>=course.timeLimitSeconds()*20L) { fail(player,session,course,"時間切れ"); return; }
        }
        showStatus(player,session,course);
    }
    private static List<BlockPos> cellsAtFeet(BoundingBox box) {
        List<BlockPos> cells=new ArrayList<>(4); int y=(int)Math.floor(box.getMinY());
        for(int x=(int)Math.floor(box.getMinX());x<=Math.floor(box.getMaxX()-1e-6);x++)
            for(int z=(int)Math.floor(box.getMinZ());z<=Math.floor(box.getMaxZ()-1e-6);z++) cells.add(new BlockPos(x,y,z));
        return cells;
    }
    @EventHandler public void serverTick(ServerTickEndEvent event) {
        int now=Bukkit.getCurrentTick();
        for(var entry:List.copyOf(sessions.entrySet())) {
            Session session=entry.getValue(); Player player=Bukkit.getPlayer(entry.getKey()); Course course=courses.get(session.course);
            if(player==null || course==null || player.isDead()) continue;
            if(session.pendingFail!=null && !session.goalReached) {
                // Damage callbacks only flag the failure; teleporting there can be undone by the rest of that movement.
                String reason=session.pendingFail; session.pendingFail=null; fail(player,session,course,reason); continue;
            }
            if(!session.practice && session.clock.phase()==RunClock.Phase.RUNNING && course.timeLimitSeconds()>0
                    && System.nanoTime()-session.startNanos>course.timeLimitSeconds()*WALL_CLOCK_MARGIN*1e9) {
                session.unmeasured=true; fail(player,session,course,"時間切れ"); continue;
            }
            if(session.goalReached || now-session.lastClientTick<=SILENCE_LIMIT) continue;
            session.unmeasured=true;
            if(now%10==0) showStatus(player,session,course);
        }
    }
    private void showStatus(Player player,Session session,Course course) {
        if(session.unmeasured && !session.goalReached) {
            player.sendActionBar(Component.text("計測不能：クライアントのtick通知が届いていません",NamedTextColor.RED)); return;
        }
        String elapsed=RunClock.seconds(session.clock.elapsed())+"秒";
        if(session.practice) {
            String flight=player.getAllowFlight() ? "　飛行オン" : "";
            String save=session.save==null ? "　セーブなし" : "";
            player.sendActionBar(Component.text("練習　"+(session.goalReached ? "ゴール　1番でQ：セーブ地点へ"
                    : switch(session.clock.phase()) {
                        case WAITING -> "動くと区間計測開始";
                        case RUNNING -> "区間 "+elapsed+(session.flew ? "（飛行あり・無効）" : "");
                        case FINISHED -> "区間 "+elapsed+"（確定）";
                    })+save+flight,NamedTextColor.AQUA));
            return;
        }
        String limit=course.timeLimitSeconds()==0 ? "" : " / "+course.timeLimitSeconds()+"秒";
        player.sendActionBar(session.goalReached
                ? Component.text("クリア "+elapsed+"　リトライ：8番か1番でQ　終了：9番",NamedTextColor.GOLD)
                : session.clock.phase()==RunClock.Phase.WAITING ? Component.text("動くと計測開始"+limit,NamedTextColor.GRAY)
                : Component.text("⏱ "+elapsed+limit));
    }
    private static void stopMotion(Player player) {
        if(player.isFlying()) player.setFlying(false);
        player.setSprinting(false); player.setVelocity(new Vector()); player.setFallDistance(0);
    }
    private static void heal(Player player) {
        var maxHealth=player.getAttribute(Attribute.MAX_HEALTH);
        if(maxHealth!=null) player.setHealth(maxHealth.getValue());
        player.setFoodLevel(20); player.setSaturation(5);
    }
    private static List<UUID> allMobs(Session session) { return session.mobs.values().stream().flatMap(List::stream).toList(); }

    // ---- Clock input ----
    @EventHandler(priority=EventPriority.MONITOR)
    public void use(PlayerInteractEvent event) {
        Session session=sessions.get(event.getPlayer().getUniqueId());
        if(session!=null && event.getAction()!=Action.PHYSICAL && systemItem(event.getItem())==null) session.clock.action();
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void swing(PlayerAnimationEvent event) {
        Session session=sessions.get(event.getPlayer().getUniqueId());
        if(session!=null && systemItem(event.getPlayer().getInventory().getItemInMainHand())==null) session.clock.action();
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void place(BlockPlaceEvent event) {
        Session session=sessions.get(event.getPlayer().getUniqueId());
        if(session!=null) session.clock.action();
    }

    // ---- Lava (flagged here, handled after the tick) ----
    @EventHandler(priority=EventPriority.HIGH)
    public void damage(EntityDamageEvent event) {
        if(!(event.getEntity() instanceof Player player)) return;
        Session session=sessions.get(player.getUniqueId()); if(session==null) return;
        Course course=courses.get(session.course); if(course==null || !course.failLava()) return;
        if(event.getCause()==EntityDamageEvent.DamageCause.LAVA) { event.setCancelled(true); if(!session.goalReached) session.pendingFail="マグマ"; }
        else if(event.getCause()==EntityDamageEvent.DamageCause.FIRE_TICK) { event.setCancelled(true); player.setFireTicks(0); }
    }
    @EventHandler(priority=EventPriority.HIGH)
    public void combust(EntityCombustByBlockEvent event) {
        if(!(event.getEntity() instanceof Player player)) return;
        Session session=sessions.get(player.getUniqueId()); if(session==null) return;
        Course course=courses.get(session.course); if(course==null || !course.failLava()) return;
        Block source=event.getCombuster();
        if((source!=null && source.getType()==Material.LAVA) || (source==null && player.isInLava())) {
            event.setCancelled(true); if(!session.goalReached) session.pendingFail="マグマ";
        }
    }

    // ---- Hotbar controls and inventory lock ----
    @EventHandler(priority=EventPriority.HIGH)
    public void drop(PlayerDropItemEvent event) {
        Player player=event.getPlayer(); Session session=sessions.get(player.getUniqueId());
        if(session==null) return;
        event.setCancelled(true);
        if(player.getInventory().getHeldItemSlot()!=0) return;
        // After this event returns, Paper puts the cancelled drop back into the inventory.
        // Resetting the hotbar first would leave that item on top of the fresh stack.
        Bukkit.getScheduler().runTask(plugin,() -> { if(player.isOnline() && sessions.get(player.getUniqueId())==session) restart(player,session); });
    }
    @EventHandler(priority=EventPriority.LOW)
    public void systemItemUse(PlayerInteractEvent event) {
        SystemItem item=systemItem(event.getItem());
        if(item==null) return;
        event.setCancelled(true);
        Player player=event.getPlayer(); Session session=sessions.get(player.getUniqueId());
        if(session==null || event.getHand()!=EquipmentSlot.HAND) return;
        if(event.getAction()!=Action.RIGHT_CLICK_AIR && event.getAction()!=Action.RIGHT_CLICK_BLOCK) return;
        switch(item) {
            case MODE -> switchMode(player,session);
            case FLIGHT -> toggleFlight(player,session);
            case SAVE -> save(player,session);
            case RETRY -> restart(player,session);
            case LEAVE -> leave(player);
        }
    }
    private void systemItems(Player player,Session session) {
        PlayerInventory inventory=player.getInventory();
        for(int slot=MODE_SLOT;slot<=LEAVE_SLOT;slot++) inventory.setItem(slot,null);
        inventory.setItem(MODE_SLOT,session.practice
                ? systemItem(SystemItem.MODE,Material.GOLD_INGOT,"本番へ切り替え","右クリック：スタートから本番（記録あり）")
                : systemItem(SystemItem.MODE,Material.FEATHER,"練習へ切り替え","右クリック：スタートから練習（記録なし）"));
        if(session.practice) {
            boolean flight=player.getAllowFlight();
            inventory.setItem(FLIGHT_SLOT,systemItem(SystemItem.FLIGHT,flight ? Material.ELYTRA : Material.PHANTOM_MEMBRANE,"飛行："+(flight ? "オン" : "オフ"),"右クリック：切り替え（飛んだ区間のタイムは無効）"));
            inventory.setItem(SAVE_SLOT,systemItem(SystemItem.SAVE,Material.ECHO_SHARD,"セーブ","右クリック：地面の上で今の状態を保存（1つだけ）"));
        }
        if(session.goalReached) {
            inventory.setItem(RETRY_SLOT,systemItem(SystemItem.RETRY,Material.LIME_DYE,"リトライ",session.practice && session.save!=null ? "右クリック：セーブ地点へ" : "右クリック：スタートからもう一度"));
            inventory.setItem(LEAVE_SLOT,systemItem(SystemItem.LEAVE,Material.OAK_DOOR,"終了","右クリック：参加者モードを終了"));
        }
    }
    @EventHandler(priority=EventPriority.LOW)
    public void inventoryClick(InventoryClickEvent event) {
        // Course items must stay in their slots: Q on slot 1 is the return button.
        if(!sessions.containsKey(event.getWhoClicked().getUniqueId())) return;
        if(event.getClickedInventory() instanceof PlayerInventory || event.isShiftClick()
                || event.getClick()==ClickType.NUMBER_KEY || event.getClick()==ClickType.SWAP_OFFHAND) event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.LOW)
    public void inventoryDrag(InventoryDragEvent event) { if(sessions.containsKey(event.getWhoClicked().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.LOW)
    public void swapHands(PlayerSwapHandItemsEvent event) { if(active(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.LOW)
    public void pickup(EntityPickupItemEvent event) { if(event.getEntity() instanceof Player player && active(player)) event.setCancelled(true); }

    // ---- Death, quit, join ----
    @EventHandler(priority=EventPriority.HIGH)
    public void death(PlayerDeathEvent event) {
        Player player=event.getEntity(); Session session=sessions.get(player.getUniqueId());
        if(session==null) return;
        event.setKeepInventory(true); event.getDrops().clear(); event.setKeepLevel(true); event.setDroppedExp(0);
        record(player,session,RunRecord.Result.FAIL);
    }
    @EventHandler(priority=EventPriority.HIGH)
    public void respawn(PlayerRespawnEvent event) {
        Player player=event.getPlayer(); Session session=sessions.get(player.getUniqueId());
        if(session==null) return;
        Course course=courses.get(session.course);
        World world=course==null ? null : Bukkit.getWorld(course.world());
        if(course==null || world==null || course.start()==null) return;
        Spot s=course.start();
        event.setRespawnLocation(session.practice && session.save!=null ? session.save.at().clone() : new Location(world,s.x(),s.y(),s.z(),s.yaw(),s.pitch()));
        Bukkit.getScheduler().runTask(plugin,() -> {
            if(!player.isOnline() || sessions.get(player.getUniqueId())!=session) return;
            if(session.practice && session.save!=null) load(player,session); else reset(player,session,course);
        });
    }
    @EventHandler public void quit(PlayerQuitEvent event) { if(active(event.getPlayer())) end(event.getPlayer()); }
    @EventHandler public void join(PlayerJoinEvent event) {
        Player player=event.getPlayer();
        if(returns.get(player.getUniqueId())!=null) Bukkit.getScheduler().runTask(plugin,() -> { if(player.isOnline() && !active(player)) restore(player); });
    }

    private ItemStack systemItem(SystemItem kind,Material material,String name,String lore) {
        ItemStack item=Icons.icon(material,name,List.of(lore));
        item.editMeta(meta -> meta.getPersistentDataContainer().set(itemKey,PersistentDataType.STRING,kind.name()));
        return item;
    }
    private SystemItem systemItem(ItemStack item) {
        if(item==null || item.getType().isAir()) return null;
        String tag=item.getItemMeta().getPersistentDataContainer().get(itemKey,PersistentDataType.STRING);
        if(tag==null) return null;
        try { return SystemItem.valueOf(tag); } catch(IllegalArgumentException e) { return null; }
    }
    private void failure(Player player,String message,Exception e) {
        Icons.tell(player,message); plugin.getLogger().log(Level.SEVERE,message,e);
    }
}
