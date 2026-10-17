package konqasasas.gauntlet.play;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import io.papermc.paper.event.packet.ClientTickEndEvent;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.logging.Level;
import konqasasas.gauntlet.course.*;
import konqasasas.gauntlet.GroundGeometry;
import konqasasas.gauntlet.data.*;
import konqasasas.gauntlet.display.Visibility;
import konqasasas.gauntlet.i18n.Msg;
import konqasasas.gauntlet.ui.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
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
 * Hotbar: 1-3 course items, 5 switch official/practice, 6 flight (practice),
 * 8 player visibility. Q with slot 1 = back to the start / save point,
 * F = save (practice). Leaving: another course, or /course leave.
 */
public final class Participants implements Listener {
    private static final String ADMIN="gauntlet.admin";
    private static final double MOVE_EPSILON_SQUARED=1e-8;
    /** Server ticks without a client tick end before the run is marked unmeasurable. */
    private static final int SILENCE_LIMIT=20;
    /** Server-clock cap on the time limit, for clients that stop reporting ticks. */
    private static final double WALL_CLOCK_MARGIN=1.5;
    /** How many times the controls are explained on entering each mode. */
    private static final int HINT_TIMES=3;
    private static final int MODE_SLOT=4,FLIGHT_SLOT=5;
    private enum SystemItem { MODE,FLIGHT }
    private record Save(Location at,ItemStack[] contents,List<PotionEffect> effects,Set<BlockPos> fired,Set<Object> scaffold,
                        Map<String,List<CourseMobs.Saved>> mobs,double along) {}
    private static final class Session {
        final String course; boolean practice; int rev;
        final RunClock clock=new RunClock();
        int lastClientTick; long startNanos,startedAt; boolean unmeasured,goalReached,flew;
        RunRecord.Reason pendingFail;
        RouteProgress route=new RouteProgress(List.of());
        /** Route distance now, and the furthest reached in this attempt. */
        double along,furthest;
        final Set<BlockPos> fired=new HashSet<>();
        final Map<String,List<UUID>> mobs=new HashMap<>();
        Save save,previousSave;
        Session(String course,boolean practice) { this.course=course; this.practice=practice; }
        Double progress() { return route.usable() ? route.fraction(furthest) : null; }
    }
    /** What the sidebar shows about a player's current course. */
    public record View(String course,boolean practice) {}
    private final JavaPlugin plugin;
    private final CourseStore courses;
    private final RunLog runs;
    private final PlayerData data;
    private final ReturnStore returns;
    private final Scaffold scaffold;
    private final CourseItems items;
    private final CourseMobs mobs=new CourseMobs();
    private final NamespacedKey itemKey;
    private final Map<UUID,Session> sessions=new HashMap<>();
    private Visibility visibility;

    public Participants(JavaPlugin plugin,CourseStore courses,RunLog runs,PlayerData data,ReturnStore returns,Scaffold scaffold,CourseItems items) {
        this.plugin=plugin; this.courses=courses; this.runs=runs; this.data=data; this.returns=returns; this.scaffold=scaffold; this.items=items;
        itemKey=new NamespacedKey(plugin,"play");
    }
    /** The visibility item is created after this class (it needs the name tags). */
    public void visibility(Visibility visibility) { this.visibility=visibility; }
    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        plugin.getServer().getPluginManager().registerEvents(mobs,plugin);
        mobs.sweep();
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
    public View view(Player player) {
        Session session=sessions.get(player.getUniqueId());
        return session==null ? null : new View(session.course,session.practice);
    }

    // ---- Entering and leaving ----
    public void enter(Player player,String id,boolean practice) {
        Course course=courses.get(id);
        if(course==null) { Msg.send(player,"play.enter.not-found",Msg.arg("id",id)); return; }
        if(!course.problems().isEmpty()) {
            Msg.send(player,"play.enter.incomplete",Msg.arg("id",id),Msg.arg("problems",String.join(", ",course.problems().stream().map(k -> Msg.plain(player,k)).toList())));
            return;
        }
        // Until progression exists, "unlocked" means published.
        if(!player.hasPermission(ADMIN) && course.status()!=CourseStatus.DONE) { Msg.send(player,"play.enter.unpublished"); return; }
        if(Bukkit.getWorld(course.world())==null) { Msg.send(player,"play.enter.world-unloaded"); return; }
        UUID uuid=player.getUniqueId();
        Session previous=sessions.remove(uuid);
        if(previous!=null) { record(player,previous,RunRecord.Result.RETIRE,null); mobs.remove(allMobs(previous)); scaffold.clear(player); }
        // Keep an older entry: it holds the inventory from before any participant run.
        else if(returns.get(uuid)==null) {
            try { returns.put(uuid,capture(player)); }
            catch(IOException|RuntimeException e) { failure(player,"play.enter.save-failed",e); return; }
        }
        Session session=new Session(id,practice); sessions.put(uuid,session);
        player.closeInventory();
        player.setAllowFlight(false);
        if(!toStart(player,course)) { end(player); return; }
        reset(player,session,course);
        Msg.send(player,practice ? "play.enter.practice" : "play.enter.official",Msg.arg("course",course.name()));
        explain(player,practice);
    }
    /** The controls, shown the first few times in each mode. */
    private void explain(Player player,boolean practice) {
        String key=practice ? "hint.practice" : "hint.official";
        if(data.counter(player.getUniqueId(),key)>=HINT_TIMES) return;
        data.increment(player.getUniqueId(),key);
        Msg.sendLines(player,key);
        player.showTitle(Title.title(Component.empty(),Msg.text(player,key+"-title"),
                Title.Times.times(Duration.ofMillis(200),Duration.ofSeconds(3),Duration.ofMillis(500))));
    }
    public void leave(Player player) {
        if(sessions.containsKey(player.getUniqueId())) { end(player); Msg.send(player,"play.leave.done"); }
        else if(returns.get(player.getUniqueId())!=null) { restore(player); Msg.send(player,"play.leave.restored"); }
        else Msg.send(player,"play.leave.not-active");
    }
    private void end(Player player) {
        Session session=sessions.remove(player.getUniqueId());
        if(session!=null) { record(player,session,RunRecord.Result.QUIT,null); mobs.remove(allMobs(session)); }
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
            Teleports.to(player,target);
            returns.remove(player.getUniqueId());
        } catch(IOException|RuntimeException e) { failure(player,"play.leave.restore-failed",e); }
    }

    // ---- Start state, failure, practice save/load ----
    private boolean toStart(Player player,Course course) {
        Spot s=course.start(); World world=Bukkit.getWorld(course.world());
        return world!=null && teleport(player,new Location(world,s.x(),s.y(),s.z(),s.yaw(),s.pitch()));
    }
    private boolean teleport(Player player,Location target) {
        World world=target.getWorld();
        if(!world.isChunkLoaded(target.getBlockX()>>4,target.getBlockZ()>>4) && !world.loadChunk(target.getBlockX()>>4,target.getBlockZ()>>4,false)) {
            Msg.send(player,"play.teleport.chunk"); return false;
        }
        if(!Teleports.to(player,target)) { Msg.send(player,"play.teleport.denied"); return false; }
        return true;
    }
    /** The start state: no scaffold, no mobs, fresh loadout, clock waiting. Flight permission stays in practice. */
    private void reset(Player player,Session session,Course course) {
        stopMotion(player); player.setFireTicks(0);
        scaffold.clear(player);
        mobs.remove(allMobs(session)); session.mobs.clear(); session.fired.clear();
        session.rev=course.rev(); session.route=new RouteProgress(course.route());
        if(player.getGameMode()!=GameMode.ADVENTURE) player.setGameMode(GameMode.ADVENTURE);
        if(!session.practice) player.setAllowFlight(false);
        heal(player);
        for(PotionEffect effect:player.getActivePotionEffects()) player.removePotionEffect(effect.getType());
        PlayerInventory inventory=player.getInventory();
        inventory.clear();
        for(int slot=0;slot<Course.COURSE_SLOTS;slot++) {
            ItemRef ref=course.loadout().get(slot);
            if(ref!=null) inventory.setItem(slot,courseItem(player,session,slot,items.create(ref)));
        }
        inventory.setHeldItemSlot(0);
        restartClock(session,0);
        systemItems(player,session);
    }
    private void restartClock(Session session,double along) {
        session.clock.reset(); session.unmeasured=false; session.goalReached=false; session.flew=false;
        session.pendingFail=null; session.lastClientTick=Bukkit.getCurrentTick();
        session.along=along; session.furthest=along;
    }
    /** Course item as handed out: the scaffold gets a localized name; slot 1 explains Q (and F). */
    private ItemStack courseItem(Player player,Session session,int slot,ItemStack item) {
        if(item==null) return null;
        boolean block=scaffold.is(item);
        if(!block && slot!=0) return item;
        Component base=(block ? Msg.text(player,"item.scaffold.name") : item.effectiveName()).decoration(TextDecoration.ITALIC,false);
        String mode=session.practice ? "practice" : "official";
        item.editMeta(meta -> {
            meta.customName(slot==0 ? base.append(Msg.text(player,"play.slot1."+mode)) : base);
            List<Component> lore=new ArrayList<>();
            if(block) lore.addAll(Msg.lines(player,"item.scaffold.lore"));
            if(slot==0) lore.addAll(Msg.lines(player,"play.slot1.lore."+mode));
            if(!lore.isEmpty()) meta.lore(lore);
        });
        return item;
    }
    /** Q on slot 1, failure: back to the start (official) or the save point (practice). */
    private void restart(Player player,Session session) {
        Course course=courses.get(session.course);
        if(course==null || !course.problems().isEmpty()) { end(player); Msg.send(player,"play.course-changed"); return; }
        record(player,session,RunRecord.Result.RETIRE,null);
        if(session.practice && session.save!=null) load(player,session);
        else if(toStart(player,course)) reset(player,session,course);
    }
    private void fail(Player player,Session session,Course course,RunRecord.Reason reason) {
        Double progress=session.practice ? (session.route.usable() ? session.route.fraction(session.along) : null) : session.progress();
        record(player,session,RunRecord.Result.FAIL,reason);
        if(data.get(player,Settings.FAIL_TITLE)) {
            Component main=progress==null ? Component.empty() : Msg.text(player,"play.fail.percent",Msg.arg("percent",RouteProgress.percent(progress,false)+"%"));
            Component sub=Msg.text(player,"play.fail.reason",Msg.arg("reason",Msg.text(player,"reason."+reason.name())));
            RunLog.Stats stats=runs.stats(course.id(),session.rev,player.getUniqueId());
            if(!session.practice && progress!=null && stats.bestProgress()!=null)
                sub=sub.append(Msg.text(player,"play.fail.best",Msg.arg("best",RouteProgress.percent(stats.bestProgress(),stats.clears()>0)+"%")));
            player.showTitle(Title.title(main,sub,Title.Times.times(Duration.ZERO,Duration.ofMillis(900),Duration.ofMillis(250))));
        }
        Sounds.system(player,Sound.ENTITY_VILLAGER_NO,.7f,1f);
        if(session.practice && session.save!=null) load(player,session);
        else if(toStart(player,course)) reset(player,session,course);
    }
    private void save(Player player,Session session) {
        if(!session.practice) return;
        if(session.goalReached) { Msg.actionBar(player,"play.save.after-goal"); return; }
        Location at=player.getLocation();
        if(!player.isOnGround() || player.isFlying() || GroundGeometry.supporting(at.getWorld(),player.getBoundingBox(),at.getY()).isEmpty()) {
            Msg.actionBar(player,"play.save.not-ground"); return;
        }
        // Whole inventory, so an equipped elytra (armor slot) comes back too.
        ItemStack[] contents=Arrays.stream(player.getInventory().getContents()).map(i -> i==null ? null : i.clone()).toArray(ItemStack[]::new);
        Map<String,List<CourseMobs.Saved>> savedMobs=new HashMap<>();
        session.mobs.forEach((key,ids) -> savedMobs.put(key,mobs.snapshot(ids)));
        // The previous save is kept so that a mistaken F can be undone.
        session.previousSave=session.save;
        session.save=new Save(at.clone(),contents,List.copyOf(player.getActivePotionEffects()),Set.copyOf(session.fired),scaffold.placed(player),savedMobs,session.along);
        Sounds.system(player,Sound.ENTITY_EXPERIENCE_ORB_PICKUP,1f,1f);
        Msg.actionBar(player,"play.save.done");
    }
    /** Goes back to the save before the last one (/course undosave). */
    public void undoSave(Player player) {
        Session session=sessions.get(player.getUniqueId());
        if(session==null || !session.practice) { Msg.send(player,"play.undo.not-practice"); return; }
        if(session.previousSave==null) { Msg.send(player,"play.undo.none"); return; }
        session.save=session.previousSave; session.previousSave=null;
        load(player,session);
        Msg.send(player,"play.undo.done");
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
        restartClock(session,save.along());
        systemItems(player,session);
    }
    private void switchMode(Player player,Session session) {
        Course course=courses.get(session.course); if(course==null) { end(player); return; }
        record(player,session,RunRecord.Result.RETIRE,null);
        session.practice=!session.practice; session.save=null; session.previousSave=null;
        if(toStart(player,course)) reset(player,session,course);
        Msg.send(player,session.practice ? "play.mode.practice" : "play.mode.official");
        explain(player,session.practice);
    }
    private void toggleFlight(Player player,Session session) {
        if(!session.practice) return;
        boolean allow=!player.getAllowFlight();
        player.setAllowFlight(allow);
        if(!allow && player.isFlying()) player.setFlying(false);
        systemItems(player,session);
        Msg.actionBar(player,allow ? "play.flight.on" : "play.flight.off");
    }
    /**
     * Logs a running official attempt. Waiting/finished/practice attempts are not
     * logged; one whose client stopped reporting ticks is logged as INVALID.
     */
    private void record(Player player,Session session,RunRecord.Result result,RunRecord.Reason reason) {
        if(session.practice || session.clock.phase()!=RunClock.Phase.RUNNING) return;
        append(player,session,session.unmeasured ? RunRecord.Result.INVALID : result,reason,session.clock.elapsed(),session.progress());
        session.clock.reset();
    }
    private void append(Player player,Session session,RunRecord.Result result,RunRecord.Reason reason,long ticks,Double progress) {
        Location at=player.getLocation();
        long now=System.currentTimeMillis();
        try {
            runs.append(new RunRecord(UUID.randomUUID(),session.course,session.rev,player.getUniqueId(),result,reason==null ? null : reason.name(),
                    ticks,session.startedAt>0 ? now-session.startedAt : null,progress,session.startedAt>0 ? session.startedAt : null,now,
                    at.getX(),at.getY(),at.getZ(),null,plugin.getPluginMeta().getVersion()));
        } catch(RuntimeException e) { plugin.getLogger().log(Level.SEVERE,"Cannot record run",e); }
    }
    private void goal(Player player,Session session,Course course) {
        boolean timed=session.clock.phase()==RunClock.Phase.RUNNING;
        session.clock.stop(); session.goalReached=true;
        session.furthest=session.route.total(); session.along=session.route.total();
        long ticks=session.clock.elapsed(); Component time=time(player,ticks);
        if(session.practice) {
            Component segment=!timed ? Component.empty() : session.flew || session.unmeasured ? Msg.text(player,"play.segment.invalid")
                    : Msg.text(player,"play.segment.time",Msg.arg("time",time),Msg.arg("ticks",ticks));
            player.showTitle(Title.title(Msg.text(player,"play.goal.practice"),segment));
            if(timed) Msg.send(player,"play.segment.chat",Msg.arg("segment",segment));
        } else if(!timed) {
            player.showTitle(Title.title(Msg.text(player,"play.goal.title"),Component.empty()));
        } else if(session.unmeasured) {
            append(player,session,RunRecord.Result.INVALID,null,ticks,1.0);
            player.showTitle(Title.title(Msg.text(player,"play.goal.title"),Msg.text(player,"play.goal.unmeasured")));
        } else {
            RunLog.Stats before=runs.stats(course.id(),session.rev,player.getUniqueId());
            append(player,session,RunRecord.Result.CLEAR,null,ticks,session.route.usable() ? 1.0 : null);
            Long best=before.bestClearTicks();
            Component comparison=best==null ? Msg.text(player,"play.goal.first-clear")
                    : ticks<best ? Msg.text(player,"play.goal.new-best",Msg.arg("diff",time(player,best-ticks)))
                    : Msg.text(player,"play.goal.best",Msg.arg("best",time(player,best)),Msg.arg("diff",time(player,ticks-best)));
            player.showTitle(Title.title(Msg.text(player,best==null ? "play.goal.first-title" : "play.goal.title"),time));
            Msg.send(player,"play.goal.chat",Msg.arg("course",course.name()),Msg.arg("time",time),Msg.arg("ticks",ticks),Msg.arg("comparison",comparison));
        }
        Sounds.system(player,Sound.UI_TOAST_CHALLENGE_COMPLETE,1f,1f);
        systemItems(player,session);
    }
    /** End of a practice segment at a trigger. */
    private void segment(Player player,Session session) {
        session.clock.stop();
        long ticks=session.clock.elapsed();
        Component segment=session.flew || session.unmeasured ? Msg.text(player,"play.segment.invalid")
                : Msg.text(player,"play.segment.time",Msg.arg("time",time(player,ticks)),Msg.arg("ticks",ticks));
        Msg.send(player,"play.segment.chat",Msg.arg("segment",segment));
    }
    private static Component time(Player player,long ticks) { return Msg.text(player,"time.seconds",Msg.arg("value",RunClock.seconds(ticks))); }

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
                if(item==null) { builderWarning(player,"editor.warn.template-deleted"); return; }
                for(int slot=0;slot<inventory.getSize();slot++) if(action.item().equals(items.ref(inventory.getItem(slot)))) return;
                int slot=action.placement().slot();
                if(slot<0) for(int i=1;i<Course.COURSE_SLOTS && slot<0;i++) { ItemStack there=inventory.getItem(i); if(there==null || there.getType().isAir()) slot=i; }
                if(slot<0) { builderWarning(player,"editor.warn.no-empty-slot"); return; }
                inventory.setItem(slot,courseItem(player,session,slot,item));
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
                if(spawned.isEmpty()) builderWarning(player,"editor.warn.mob-failed",Msg.arg("mob",action.mob()));
                session.mobs.put(key,spawned);
            }
            case SOUND -> Sounds.course(player,action.sound(),action.volume(),action.pitch());
        }
    }
    private static void builderWarning(Player player,String key,TagResolver... args) {
        if(player.hasPermission(ADMIN)) Msg.send(player,key,args);
    }

    // ---- Per client tick ----
    @EventHandler public void clientTick(ClientTickEndEvent event) {
        Player player=event.getPlayer(); Session session=sessions.get(player.getUniqueId());
        if(session==null) return;
        session.lastClientTick=Bukkit.getCurrentTick();
        Course course=courses.get(session.course);
        if(course==null || !course.problems().isEmpty()) { end(player); Msg.send(player,"play.course-changed"); return; }
        if(player.isDead()) return;
        Location at=player.getLocation(); boolean sameWorld=at.getWorld().getUID().equals(course.world());
        if(!session.goalReached) {
            if(!sameWorld || (course.failOutside() && !course.region().contains(at.getX(),at.getY(),at.getZ()))) { fail(player,session,course,RunRecord.Reason.OUT); return; }
            if(course.failLava() && player.isInLava()) { fail(player,session,course,RunRecord.Reason.LAVA); return; }
        }
        boolean moved=false;
        if(session.clock.phase()==RunClock.Phase.WAITING) {
            Spot s=course.start();
            moved=session.save==null || !session.practice
                    ? at.toVector().distanceSquared(new Vector(s.x(),s.y(),s.z()))>MOVE_EPSILON_SQUARED
                    : at.toVector().distanceSquared(session.save.at().toVector())>MOVE_EPSILON_SQUARED;
        }
        if(session.clock.tick(moved)) { session.startNanos=System.nanoTime(); session.startedAt=System.currentTimeMillis(); }
        if(player.isFlying()) session.flew=true;
        if(session.clock.phase()!=RunClock.Phase.WAITING && !session.goalReached) {
            if(session.route.usable()) {
                session.along=session.route.locate(at.getX(),at.getY(),at.getZ(),session.along);
                session.furthest=Math.max(session.furthest,session.along);
            }
            for(BlockPos pos:cellsAtFeet(player.getBoundingBox())) {
                Trigger trigger=course.trigger(pos);
                if(trigger!=null && !session.fired.contains(pos)) fire(player,session,course,trigger);
            }
            if(player.isOnGround()) for(Block block:GroundGeometry.supporting(at.getWorld(),player.getBoundingBox(),at.getY()))
                if(course.isGoal(block.getX(),block.getY(),block.getZ())) { goal(player,session,course); break; }
            if(!session.goalReached && !session.practice && session.clock.phase()==RunClock.Phase.RUNNING
                    && course.timeLimitSeconds()>0 && session.clock.elapsed()>=course.timeLimitSeconds()*20L) { fail(player,session,course,RunRecord.Reason.TIMEOUT); return; }
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
                RunRecord.Reason reason=session.pendingFail; session.pendingFail=null; fail(player,session,course,reason); continue;
            }
            if(!session.practice && session.clock.phase()==RunClock.Phase.RUNNING && course.timeLimitSeconds()>0
                    && System.nanoTime()-session.startNanos>course.timeLimitSeconds()*WALL_CLOCK_MARGIN*1e9) {
                session.unmeasured=true; fail(player,session,course,RunRecord.Reason.TIMEOUT); continue;
            }
            if(session.goalReached || now-session.lastClientTick<=SILENCE_LIMIT) continue;
            session.unmeasured=true;
            if(now%10==0) showStatus(player,session,course);
        }
    }
    private void showStatus(Player player,Session session,Course course) {
        if(session.unmeasured && !session.goalReached) { Msg.actionBar(player,"play.status.unmeasured"); return; }
        Component elapsed=time(player,session.clock.elapsed());
        String percent=!session.route.usable() ? "" : "  ·  "+RouteProgress.percent(
                session.route.fraction(session.practice ? session.along : session.furthest),session.goalReached)+"%";
        if(session.practice) {
            Component state=session.goalReached ? Msg.text(player,"play.status.practice.goal")
                    : switch(session.clock.phase()) {
                        case WAITING -> Msg.text(player,"play.status.practice.waiting");
                        case RUNNING -> Msg.text(player,session.flew ? "play.status.practice.flew" : "play.status.practice.running",Msg.arg("time",elapsed));
                        case FINISHED -> Msg.text(player,"play.status.practice.finished",Msg.arg("time",elapsed));
                    };
            Msg.actionBar(player,"play.status.practice",Msg.arg("state",state),Msg.arg("progress",percent),
                    Msg.arg("save",session.save==null ? Msg.text(player,"play.status.practice.no-save") : Component.empty()),
                    Msg.arg("flight",player.getAllowFlight() ? Msg.text(player,"play.status.practice.flight") : Component.empty()));
            return;
        }
        String limit=course.timeLimitSeconds()==0 ? "" : " / "+Msg.plain(player,"time.seconds",Msg.arg("value",course.timeLimitSeconds()));
        if(session.goalReached) Msg.actionBar(player,"play.status.goal",Msg.arg("time",elapsed),Msg.arg("progress",percent));
        else if(session.clock.phase()==RunClock.Phase.WAITING) Msg.actionBar(player,"play.status.waiting",Msg.arg("limit",limit));
        else Msg.actionBar(player,"play.status.running",Msg.arg("time",elapsed),Msg.arg("limit",limit),Msg.arg("progress",percent));
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
    private boolean control(ItemStack item) { return systemItem(item)!=null || (visibility!=null && visibility.is(item)); }
    @EventHandler(priority=EventPriority.MONITOR)
    public void use(PlayerInteractEvent event) {
        Session session=sessions.get(event.getPlayer().getUniqueId());
        if(session!=null && event.getAction()!=Action.PHYSICAL && !control(event.getItem())) session.clock.action();
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void swing(PlayerAnimationEvent event) {
        Session session=sessions.get(event.getPlayer().getUniqueId());
        if(session!=null && !control(event.getPlayer().getInventory().getItemInMainHand())) session.clock.action();
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
        if(event.getCause()==EntityDamageEvent.DamageCause.LAVA) { event.setCancelled(true); if(!session.goalReached) session.pendingFail=RunRecord.Reason.LAVA; }
        else if(event.getCause()==EntityDamageEvent.DamageCause.FIRE_TICK) { event.setCancelled(true); player.setFireTicks(0); }
    }
    @EventHandler(priority=EventPriority.HIGH)
    public void combust(EntityCombustByBlockEvent event) {
        if(!(event.getEntity() instanceof Player player)) return;
        Session session=sessions.get(player.getUniqueId()); if(session==null) return;
        Course course=courses.get(session.course); if(course==null || !course.failLava()) return;
        Block source=event.getCombuster();
        if((source!=null && source.getType()==Material.LAVA) || (source==null && player.isInLava())) {
            event.setCancelled(true); if(!session.goalReached) session.pendingFail=RunRecord.Reason.LAVA;
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
    /** F: save in practice. Hands are never swapped during a course. */
    @EventHandler(priority=EventPriority.LOW)
    public void swapHands(PlayerSwapHandItemsEvent event) {
        Session session=sessions.get(event.getPlayer().getUniqueId());
        if(session==null) return;
        event.setCancelled(true);
        if(session.practice) save(event.getPlayer(),session);
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
        }
    }
    private void systemItems(Player player,Session session) {
        PlayerInventory inventory=player.getInventory();
        for(int slot=Course.COURSE_SLOTS;slot<9;slot++) inventory.setItem(slot,null);
        inventory.setItem(MODE_SLOT,session.practice
                ? systemItem(player,SystemItem.MODE,Material.GOLD_INGOT,"play.item.to-official")
                : systemItem(player,SystemItem.MODE,Material.FEATHER,"play.item.to-practice"));
        if(session.practice) {
            boolean flight=player.getAllowFlight();
            inventory.setItem(FLIGHT_SLOT,systemItem(player,SystemItem.FLIGHT,flight ? Material.ELYTRA : Material.PHANTOM_MEMBRANE,flight ? "play.item.flight-on" : "play.item.flight-off"));
        }
        if(visibility!=null) inventory.setItem(Visibility.SLOT,visibility.item(player));
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
    public void pickup(EntityPickupItemEvent event) { if(event.getEntity() instanceof Player player && active(player)) event.setCancelled(true); }

    // ---- Death, quit, join ----
    @EventHandler(priority=EventPriority.HIGH)
    public void death(PlayerDeathEvent event) {
        Player player=event.getEntity(); Session session=sessions.get(player.getUniqueId());
        if(session==null) return;
        event.setKeepInventory(true); event.getDrops().clear(); event.setKeepLevel(true); event.setDroppedExp(0);
        record(player,session,RunRecord.Result.FAIL,RunRecord.Reason.DEATH);
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

    private ItemStack systemItem(Player player,SystemItem kind,Material material,String key) {
        ItemStack item=Msg.icon(player,material,key);
        item.editMeta(meta -> meta.getPersistentDataContainer().set(itemKey,PersistentDataType.STRING,kind.name()));
        return item;
    }
    private SystemItem systemItem(ItemStack item) {
        if(item==null || item.getType().isAir()) return null;
        String tag=item.getItemMeta().getPersistentDataContainer().get(itemKey,PersistentDataType.STRING);
        if(tag==null) return null;
        try { return SystemItem.valueOf(tag); } catch(IllegalArgumentException e) { return null; }
    }
    private void failure(Player player,String key,Exception e) {
        Msg.send(player,key); plugin.getLogger().log(Level.SEVERE,Msg.plain(player,key),e);
    }
}
