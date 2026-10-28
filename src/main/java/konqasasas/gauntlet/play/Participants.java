package konqasasas.gauntlet.play;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import io.papermc.paper.event.packet.ClientTickEndEvent;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;
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
 * Courses of an era are played in one go: after the goal, stepping on another
 * course's start in the same lane moves on to that course (and records it as
 * reached). A player keeps their lane of the era (or of a course outside the eras)
 * until they leave; logging out mid-era puts them back at that course's start,
 * in whatever lane is free, when they come back.
 *
 * Hotbar: 1-3 course items, 5 switch official/practice, 6 flight (practice),
 * 8 player visibility. Q with slot 1 = back to the start / save point,
 * F = save (practice). Leaving: another course, or /course leave.
 * Status goes to the action bar (no titles); results of actions go to chat.
 */
public final class Participants implements Listener {
    private static final String ADMIN="gauntlet.admin";
    private static final double MOVE_EPSILON_SQUARED=1e-8;
    /** Server ticks without a client tick end before the run is marked unmeasurable. */
    private static final int SILENCE_LIMIT=20;
    /** Server-clock cap on the time limit, for clients that stop reporting ticks. */
    private static final double WALL_CLOCK_MARGIN=1.5;
    private static final int MODE_SLOT=4,FLIGHT_SLOT=5;
    private enum SystemItem { MODE,FLIGHT }
    private record Save(Location at,ItemStack[] contents,List<PotionEffect> effects,Set<BlockPos> fired,Set<Object> scaffold,
                        Map<String,List<CourseMobs.Saved>> mobs,List<WaterFlows.Saved> water,double along,int segment) {}
    private static final class Session {
        /** The course changes when moving on in an era; the space (era) and the lane never do. */
        String course; final String space; final int lane; boolean practice; int rev;
        /** The course definition and its copy moved into this lane (rebuilt when either changes). */
        Course base,laned; BlockPos offset;
        /** Where the clock waits for the first move (the start, or where the course was switched to). */
        Vector waitAt=new Vector();
        /** The goal block reached (in this lane), where a fall after the goal goes back to. */
        BlockPos goalAt;
        /** Generated courses: the module entered last (index into segments). */
        int segment=-1;
        /**
         * The module being timed (a segment, or a module course played alone): since which client tick
         * (RunClock.received), and whether it is still clean (no save loaded, no flight since entering it).
         * passOutside: the course starts outside its first module, whose time counts once the player is in its box.
         */
        long passFrom; boolean passClean,passOutside;
        final RunClock clock=new RunClock();
        int lastClientTick; long startNanos,startedAt; boolean unmeasured,goalReached,flew;
        RunRecord.Reason pendingFail;
        RouteProgress route=new RouteProgress(List.of());
        /** Route distance now, and the furthest reached in this attempt. */
        double along,furthest;
        final Set<BlockPos> fired=new HashSet<>();
        final Map<String,List<UUID>> mobs=new HashMap<>();
        Save save,previousSave;
        Session(String course,String space,int lane,boolean practice) { this.course=course; this.space=space; this.lane=lane; this.practice=practice; }
        Double progress() { return route.usable() ? route.fraction(furthest) : null; }
    }
    /** What the sidebar shows about a player's current course. space: the era (or course) owning the lane; lane: 0 = lane 1. */
    public record View(String course,String space,int lane,boolean practice) {}
    private final JavaPlugin plugin;
    private final CourseStore courses;
    private final RunLog runs;
    private final PlayerData data;
    private final ReturnStore returns;
    private final Scaffold scaffold;
    private final CourseItems items;
    private final CourseMobs mobs=new CourseMobs();
    private final WaterFlows water;
    private final TriggerActions actions;
    private final NamespacedKey itemKey;
    /** On items handed out in a set number: the scaffold is not refilled. */
    private final NamespacedKey limitedKey;
    private final Map<UUID,Session> sessions=new HashMap<>();
    private final Lanes lanes;
    private final Spaces spaces;
    private final Progress progress;
    private Visibility visibility;
    /** Where someone resuming after a logout waits when every lane is taken (null: where they are). */
    private Supplier<Location> waitingSpot=() -> null;
    private RankUps rankUps;
    private konqasasas.gauntlet.replay.ReplayRecorder replays;
    private ModuleTimes moduleTimes;

    public Participants(JavaPlugin plugin,CourseStore courses,RunLog runs,PlayerData data,ReturnStore returns,Scaffold scaffold,CourseItems items,WaterFlows water,
                        Lanes lanes,Spaces spaces,Progress progress) {
        this.plugin=plugin; this.courses=courses; this.runs=runs; this.data=data; this.returns=returns; this.scaffold=scaffold; this.items=items; this.water=water;
        this.lanes=lanes; this.spaces=spaces; this.progress=progress;
        itemKey=new NamespacedKey(plugin,"play");
        limitedKey=new NamespacedKey(plugin,"limited");
        actions=new TriggerActions(items,mobs,water);
        lanes.admit(this::admitted,this::active);
    }
    /** Shared with the builder kit: one tracker, so neither removes the other's mobs as leftovers. */
    CourseMobs mobs() { return mobs; }
    TriggerActions actions() { return actions; }
    WaterFlows water() { return water; }
    /** The visibility item is created after this class (it needs the name tags). */
    public void visibility(Visibility visibility) { this.visibility=visibility; }
    public void waitingSpot(Supplier<Location> spot) { waitingSpot=spot; }
    public void rankUps(RankUps rankUps) { this.rankUps=rankUps; }
    /** Official runs are recorded for replays; only clears are kept. */
    public void replays(konqasasas.gauntlet.replay.ReplayRecorder recorder) { replays=recorder; }
    public void moduleTimes(ModuleTimes times) { moduleTimes=times; }
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
            if(player!=null) { keepResume(player); end(player); }
        }
        sessions.clear();
    }
    public boolean active(Player player) { return sessions.containsKey(player.getUniqueId()); }
    public View view(Player player) {
        Session session=sessions.get(player.getUniqueId());
        return session==null ? null : new View(session.course,session.space,session.lane,session.practice);
    }
    /** Everyone in a course, with their lane. */
    public Map<UUID,View> views() {
        Map<UUID,View> views=new HashMap<>();
        sessions.forEach((id,s) -> views.put(id,new View(s.course,s.space,s.lane,s.practice)));
        return views;
    }
    /** The course as played in the session's lane; null when it was deleted, left the era, or the lane is gone. */
    private Course course(Session session) {
        Course base=courses.get(session.course);
        if(base==null) return null;
        Space space=spaces.of(base);
        if(!space.key().equals(session.space) || session.lane>=space.laneCount()) return null;
        BlockPos d=space.laneOffset(session.lane);
        if(base!=session.base || !d.equals(session.offset)) { session.base=base; session.offset=d; session.laned=spaces.inLane(base,session.lane); }
        return session.laned;
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
        Space space=spaces.of(course);
        String spaceProblem=space.lanesOverlap() ? "problem.lane-overlap"
                : space.isEra() && (space.region()==null || !space.region().contains(course.region())) ? "problem.outside-era" : null;
        if(spaceProblem!=null) { Msg.send(player,"play.enter.incomplete",Msg.arg("id",id),Msg.arg("problems",Msg.plain(player,spaceProblem))); return; }
        UUID uuid=player.getUniqueId();
        Era era=spaces.era(course);
        if(era!=null && !player.hasPermission(ADMIN) && progress.rank(uuid)<era.requiredRank()) {
            Msg.send(player,"play.era.rank-needed",Msg.arg("era",era.name()),Msg.arg("rank",era.requiredRank())); return;
        }
        if(!mayStart(player,course)) {
            Msg.send(player,"play.era.not-reached",Msg.arg("course",course.name())); return;
        }
        // Every lane taken (or being synced): wait in line; the current course, if any, goes on meanwhile.
        int lane=lanes.claim(space,uuid);
        if(lane<0) {
            Msg.send(player,"play.lane.waiting",Msg.arg("course",course.name()),Msg.arg("position",lanes.enqueue(uuid,space,id,practice)));
            return;
        }
        lanes.dequeue(uuid);
        Session previous=sessions.remove(uuid);
        if(previous!=null) { record(player,previous,RunRecord.Result.RETIRE,null); mobs.remove(allMobs(previous)); scaffold.clear(player); water.clear(player); }
        // Keep an older entry: it holds the inventory from before any participant run.
        else if(returns.get(uuid)==null) {
            try { returns.put(uuid,capture(player)); }
            catch(IOException|RuntimeException e) { lanes.release(uuid); failure(player,"play.enter.save-failed",e); return; }
        }
        Session session=new Session(id,space.key(),lane,practice); sessions.put(uuid,session);
        Course laned=course(session);
        player.closeInventory();
        player.setAllowFlight(false);
        if(!toStart(player,laned)) { end(player); return; }
        reset(player,session,laned,true);
        if(visibility!=null) visibility.apply(player);
        if(era!=null) progress.reach(uuid,id);
        progress.clearResume(uuid);
        Msg.send(player,practice ? "play.enter.practice" : "play.enter.official",Msg.arg("course",course.name()));
        if(space.laneCount()>1) Msg.send(player,"play.lane.entered",Msg.arg("lane",lane+1),Msg.arg("count",space.laneCount()));
    }
    /** In an era (with the rank it needs), the first course is open to all and the others once reached (builders: all). */
    public boolean mayStart(Player player,Course course) {
        Era era=spaces.era(course);
        if(era==null || player.hasPermission(ADMIN)) return true;
        if(progress.rank(player.getUniqueId())<era.requiredRank()) return false;
        return era.first(course.id()) || progress.reached(player.getUniqueId(),course.id());
    }
    /** A waiting player whose turn came. */
    private void admitted(Player player,String course,boolean practice) {
        Sounds.system(player,Sound.BLOCK_NOTE_BLOCK_PLING,1f,1.5f);
        enter(player,course,practice);
    }
    public void leave(Player player) {
        progress.clearResume(player.getUniqueId());
        if(lanes.dequeue(player.getUniqueId())) { Msg.send(player,"play.lane.left-queue"); if(!active(player)) return; }
        if(sessions.containsKey(player.getUniqueId())) { end(player); Msg.send(player,"play.leave.done"); }
        else if(returns.get(player.getUniqueId())!=null) { restore(player); Msg.send(player,"play.leave.restored"); }
        else Msg.send(player,"play.leave.not-active");
    }
    private void end(Player player) {
        Session session=sessions.remove(player.getUniqueId());
        if(session!=null) { record(player,session,RunRecord.Result.QUIT,null); mobs.remove(allMobs(session)); }
        lanes.release(player.getUniqueId());
        scaffold.clear(player); water.clear(player); stopMotion(player);
        for(PotionEffect effect:player.getActivePotionEffects()) player.removePotionEffect(effect.getType());
        player.sendActionBar(Component.empty());
        restore(player);
        if(visibility!=null) visibility.apply(player);
    }
    /** Logging out (or the server stopping) mid-era: come back at this course's start. */
    private void keepResume(Player player) {
        Session session=sessions.get(player.getUniqueId());
        Course course=session==null ? null : courses.get(session.course);
        if(course!=null && spaces.era(course)!=null) progress.resume(player.getUniqueId(),course.id());
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
            Inventories.apply(player.getInventory(),ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(saved.inventory())));
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
    /**
     * The start state: no scaffold, no mobs, fresh loadout, clock waiting at the start.
     * Flight permission stays in practice. stop: also stop the player's motion (not when walking on into the next course).
     */
    private void reset(Player player,Session session,Course course,boolean stop) {
        if(stop) stopMotion(player);
        player.setFireTicks(0);
        if(replays!=null) replays.discard(player);
        scaffold.clear(player); water.clear(player);
        mobs.remove(allMobs(session)); session.mobs.clear(); session.fired.clear();
        session.rev=course.rev(); session.route=new RouteProgress(course.route());
        if(player.getGameMode()!=GameMode.ADVENTURE) player.setGameMode(GameMode.ADVENTURE);
        if(!session.practice) player.setAllowFlight(false);
        heal(player);
        for(PotionEffect effect:player.getActivePotionEffects()) player.removePotionEffect(effect.getType());
        PlayerInventory inventory=player.getInventory();
        // Replace slot by slot (no clearing), so unchanged items do not bounce.
        ItemStack[] contents=new ItemStack[inventory.getSize()];
        for(int slot=0;slot<Course.COURSE_SLOTS;slot++) {
            ItemRef ref=course.loadout().get(slot);
            if(ref!=null) contents[slot]=courseItem(player,session,slot,items.create(ref));
        }
        systemItems(player,session,contents);
        Inventories.apply(inventory,contents);
        inventory.setHeldItemSlot(0);
        restartClock(session,0);
        Spot s=course.start(); session.waitAt=new Vector(s.x(),s.y(),s.z()); session.goalAt=null;
        // A generated course starts in its first module, with that module's items and time.
        session.segment=-1;
        if(!course.segments().isEmpty()) {
            enterSegment(player,session,course,0);
            session.passOutside=!course.segments().getFirst().box().contains(s.x(),s.y(),s.z());
        }
        else beginPass(session);
    }
    /**
     * Entering a module of a generated course: its items replace slots 1-3 (a count
     * other than 0 gives that many, not refilled). Its time counts from here.
     */
    private void enterSegment(Player player,Session session,Course course,int index) {
        Segment segment=course.segments().get(index);
        session.segment=index;
        beginPass(session);
        PlayerInventory inventory=player.getInventory();
        for(int slot=0;slot<Course.COURSE_SLOTS;slot++) {
            ItemRef ref=segment.loadout().get(slot);
            ItemStack item=ref==null ? null : courseItem(player,session,slot,items.create(ref));
            int count=segment.counts().get(slot);
            if(item!=null && count>0) {
                item.setAmount(count);
                item.editMeta(meta -> meta.getPersistentDataContainer().set(limitedKey,PersistentDataType.BYTE,(byte)1));
            }
            Inventories.put(inventory,slot,item);
        }
        if(index>0) Sounds.system(player,Sound.BLOCK_NOTE_BLOCK_HAT,.6f,1.4f);
    }
    /** The next module, when the player is inside its box. */
    private void advanceSegment(Player player,Session session,Course course,Location at) {
        if(session.passOutside && session.segment>=0 && course.segments().get(session.segment).box().contains(at.getX(),at.getY(),at.getZ())) {
            session.passFrom=session.clock.received(); session.passOutside=false;
        }
        int next=session.segment+1;
        if(next<course.segments().size() && course.segments().get(next).box().contains(at.getX(),at.getY(),at.getZ())) {
            finishPass(player,session,course);
            enterSegment(player,session,course,next);
        }
    }
    private static void beginPass(Session session) { session.passFrom=session.clock.received(); session.passClean=true; session.passOutside=false; }
    /**
     * Leaving the module being timed (into the next one, or at the goal): its time goes to the module
     * times when nothing reset it on the way and the clock was running from its start.
     */
    private void finishPass(Player player,Session session,Course course) {
        if(moduleTimes==null || !session.passClean || session.passOutside || session.unmeasured || session.clock.phase()==RunClock.Phase.WAITING) return;
        session.passClean=false;
        long ticks=session.clock.received()-session.passFrom;
        if(ticks<=0) return;
        if(!course.segments().isEmpty()) {
            if(session.segment<0) return;
            Segment segment=course.segments().get(session.segment);
            moduleTimes.add(segment.module(),segment.moduleRev(),player.getUniqueId(),ticks,session.practice,true);
        } else if(course.module()!=null) moduleTimes.add(course.id(),course.rev(),player.getUniqueId(),ticks,session.practice,false);
    }
    /** The time limit (ticks; 0 = none): a generated course's is fixed when it was generated. */
    private static long limitTicks(Course course,Session session) {
        return course.segments().isEmpty() ? course.timeLimitSeconds()*20L : course.limitTicks();
    }
    private void restartClock(Session session,double along) {
        session.clock.reset(); session.unmeasured=false; session.goalReached=false; session.flew=false;
        session.pendingFail=null; session.lastClientTick=Bukkit.getCurrentTick();
        session.along=along; session.furthest=along;
    }
    /** Course item as handed out: the scaffold gets a localized name; slot 1 lists Q (and F) in its description. */
    private ItemStack courseItem(Player player,Session session,int slot,ItemStack item) {
        if(item==null) return null;
        boolean block=scaffold.is(item);
        if(!block && slot!=0) return item;
        String mode=session.practice ? "practice" : "official";
        item.editMeta(meta -> {
            if(block) meta.customName(Msg.text(player,"item.scaffold.name").decoration(TextDecoration.ITALIC,false));
            List<Component> lore=new ArrayList<>();
            if(block) lore.addAll(Msg.lines(player,"item.scaffold.lore"));
            if(slot==0) lore.addAll(Msg.lines(player,"play.slot1.lore."+mode));
            if(!lore.isEmpty()) meta.lore(lore);
        });
        return item;
    }
    /** Q on slot 1, failure: back to the start (official) or the save point (practice). */
    private void restart(Player player,Session session) {
        Course course=course(session);
        if(course==null || !course.problems().isEmpty()) { end(player); Msg.send(player,"play.course-changed"); return; }
        record(player,session,RunRecord.Result.RETIRE,null);
        if(session.practice && session.save!=null) load(player,session);
        else if(toStart(player,course)) reset(player,session,course,true);
    }
    private void fail(Player player,Session session,Course course,RunRecord.Reason reason) {
        record(player,session,RunRecord.Result.FAIL,reason);
        Sounds.system(player,Sound.ENTITY_VILLAGER_NO,.7f,1f);
        if(session.practice && session.save!=null) load(player,session);
        else if(toStart(player,course)) reset(player,session,course,true);
    }
    private void save(Player player,Session session) {
        if(!session.practice) return;
        if(session.goalReached) { Msg.send(player,"play.save.after-goal"); return; }
        Location at=player.getLocation();
        if(!player.isOnGround() || player.isFlying() || GroundGeometry.supporting(at.getWorld(),player.getBoundingBox(),at.getY()).isEmpty()) {
            Msg.send(player,"play.save.not-ground"); return;
        }
        // Whole inventory, so an equipped elytra (armor slot) comes back too.
        ItemStack[] contents=Arrays.stream(player.getInventory().getContents()).map(i -> i==null ? null : i.clone()).toArray(ItemStack[]::new);
        Map<String,List<CourseMobs.Saved>> savedMobs=new HashMap<>();
        session.mobs.forEach((key,ids) -> savedMobs.put(key,mobs.snapshot(ids)));
        int segment=session.segment;
        // The previous save is kept so that a mistaken F can be undone.
        session.previousSave=session.save;
        session.save=new Save(at.clone(),contents,List.copyOf(player.getActivePotionEffects()),Set.copyOf(session.fired),scaffold.placed(player),savedMobs,water.snapshot(player),session.along,segment);
        Sounds.system(player,Sound.ENTITY_EXPERIENCE_ORB_PICKUP,1f,1f);
        Msg.send(player,"play.save.done");
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
        Inventories.apply(player.getInventory(),Arrays.stream(save.contents()).map(i -> i==null ? null : i.clone()).toArray(ItemStack[]::new));
        for(PotionEffect effect:player.getActivePotionEffects()) player.removePotionEffect(effect.getType());
        player.addPotionEffects(save.effects());
        session.fired.clear(); session.fired.addAll(save.fired());
        mobs.remove(allMobs(session)); session.mobs.clear();
        save.mobs().forEach((key,saved) -> session.mobs.put(key,mobs.respawn(saved)));
        // Trigger water as it was at the save, with the time it had left.
        water.restore(player,save.water());
        player.getInventory().setHeldItemSlot(0);
        restartClock(session,save.along());
        session.segment=save.segment(); session.passClean=false;
        systemItems(player,session);
    }
    private void switchMode(Player player,Session session) {
        Course course=course(session); if(course==null) { end(player); return; }
        record(player,session,RunRecord.Result.RETIRE,null);
        session.practice=!session.practice; session.save=null; session.previousSave=null;
        if(toStart(player,course)) reset(player,session,course,true);
        Msg.send(player,session.practice ? "play.mode.practice" : "play.mode.official");
    }
    private void toggleFlight(Player player,Session session) {
        if(!session.practice) return;
        boolean allow=!player.getAllowFlight();
        player.setAllowFlight(allow);
        if(!allow && player.isFlying()) player.setFlying(false);
        systemItems(player,session);
    }
    /**
     * Logs a running official attempt. Waiting/finished/practice attempts are not
     * logged; one whose client stopped reporting ticks is logged as INVALID.
     */
    private void record(Player player,Session session,RunRecord.Result result,RunRecord.Reason reason) {
        // Only clears keep their replay.
        if(replays!=null) replays.discard(player);
        if(session.practice || session.clock.phase()!=RunClock.Phase.RUNNING) return;
        append(player,session,session.unmeasured ? RunRecord.Result.INVALID : result,reason,session.clock.elapsed(),session.progress());
        session.clock.reset();
    }
    /** Logs the attempt; its run id. */
    private UUID append(Player player,Session session,RunRecord.Result result,RunRecord.Reason reason,long ticks,Double progress) {
        Location at=player.getLocation();
        long now=System.currentTimeMillis();
        UUID id=UUID.randomUUID();
        try {
            runs.append(new RunRecord(id,session.course,session.rev,player.getUniqueId(),result,reason==null ? null : reason.name(),
                    ticks,session.startedAt>0 ? now-session.startedAt : null,progress,session.startedAt>0 ? session.startedAt : null,now,
                    at.getX(),at.getY(),at.getZ(),session.lane+1,plugin.getPluginMeta().getVersion()));
        } catch(RuntimeException e) { plugin.getLogger().log(Level.SEVERE,"Cannot record run",e); }
        return id;
    }
    private void goal(Player player,Session session,Course course) {
        finishPass(player,session,course);
        boolean timed=session.clock.phase()==RunClock.Phase.RUNNING;
        session.clock.stop(); session.goalReached=true;
        session.furthest=session.route.total(); session.along=session.route.total();
        long ticks=session.clock.elapsed(); Component time=time(player,ticks);
        if(session.practice) {
            Component segment=session.flew || session.unmeasured ? Msg.text(player,"play.segment.invalid")
                    : Msg.text(player,"play.segment.time",Msg.arg("time",time),Msg.arg("ticks",ticks));
            if(timed) Msg.send(player,"play.segment.chat",Msg.arg("segment",segment));
        } else if(!timed) {
            // Reached without the clock running: nothing to record.
        } else if(session.unmeasured) {
            if(replays!=null) replays.discard(player);
            append(player,session,RunRecord.Result.INVALID,null,ticks,session.route.usable() ? 1.0 : null);
            Msg.send(player,"play.goal.unmeasured");
        } else {
            RunLog.Stats before=runs.stats(course.id(),session.rev,player.getUniqueId());
            UUID run=append(player,session,RunRecord.Result.CLEAR,null,ticks,session.route.usable() ? 1.0 : null);
            if(replays!=null) replays.finish(player,run,ticks);
            Long best=before.bestClearTicks();
            Component comparison=best==null ? Msg.text(player,"play.goal.first-clear")
                    : ticks<best ? Msg.text(player,"play.goal.new-best",Msg.arg("diff",time(player,best-ticks)))
                    : Msg.text(player,"play.goal.best",Msg.arg("best",time(player,best)),Msg.arg("diff",time(player,ticks-best)));
            Msg.send(player,"play.goal.chat",Msg.arg("course",course.name()),Msg.arg("time",time),Msg.arg("ticks",ticks),Msg.arg("comparison",comparison));
            rankUp(player,course);
        }
        Sounds.system(player,Sound.UI_TOAST_CHALLENGE_COMPLETE,1f,1f);
        systemItems(player,session);
    }
    /** An official clear of an era's boss raises the rank (never lowers it). */
    private void rankUp(Player player,Course course) {
        Era era=spaces.era(course);
        int rank=era==null ? 0 : era.rankFor(course.id());
        int before=progress.rank(player.getUniqueId());
        if(rank>0 && progress.raise(player.getUniqueId(),rank) && rankUps!=null) rankUps.rankedUp(player,before,rank);
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
        actions.run(player,Bukkit.getWorld(course.world()),key,action,session.mobs,(slot,item) -> courseItem(player,session,slot,item));
    }

    // ---- Per client tick ----
    @EventHandler public void clientTick(ClientTickEndEvent event) {
        Player player=event.getPlayer(); Session session=sessions.get(player.getUniqueId());
        if(session==null) return;
        session.lastClientTick=Bukkit.getCurrentTick();
        Course course=course(session);
        if(course==null || !course.problems().isEmpty()) { end(player); Msg.send(player,"play.course-changed"); return; }
        if(player.isDead()) return;
        Location at=player.getLocation(); boolean sameWorld=at.getWorld().getUID().equals(course.world());
        if(!session.goalReached) {
            if(!sameWorld || (course.failOutside() && !course.region().contains(at.getX(),at.getY(),at.getZ()))) { fail(player,session,course,RunRecord.Reason.OUT); return; }
            if(course.failLava() && player.isInLava()) { fail(player,session,course,RunRecord.Reason.LAVA); return; }
        } else if(afterGoal(player,session,course,at,sameWorld)) return;
        boolean moved=false;
        if(session.clock.phase()==RunClock.Phase.WAITING) {
            moved=session.save==null || !session.practice
                    ? at.toVector().distanceSquared(session.waitAt)>MOVE_EPSILON_SQUARED
                    : at.toVector().distanceSquared(session.save.at().toVector())>MOVE_EPSILON_SQUARED;
        }
        if(session.clock.tick(moved)) {
            session.startNanos=System.nanoTime(); session.startedAt=System.currentTimeMillis();
            // The module being timed starts with the run, not when the player was put at the start.
            if(!session.passOutside) session.passFrom=session.clock.received();
            if(replays!=null && !session.practice) replays.begin(player,course);
        }
        if(player.isFlying()) { session.flew=true; session.passClean=false; }
        if(session.clock.phase()!=RunClock.Phase.WAITING && !session.goalReached) {
            if(session.route.usable()) {
                session.along=session.route.locate(at.getX(),at.getY(),at.getZ(),session.along);
                session.furthest=Math.max(session.furthest,session.along);
            }
            if(!course.segments().isEmpty()) advanceSegment(player,session,course,at);
            for(BlockPos pos:platesPressed(at.getWorld(),player.getBoundingBox())) {
                Trigger trigger=course.trigger(pos);
                if(trigger!=null && !session.fired.contains(pos)) fire(player,session,course,trigger);
            }
            if(player.isOnGround()) for(Block block:GroundGeometry.supporting(at.getWorld(),player.getBoundingBox(),at.getY()))
                if(course.isGoal(block.getX(),block.getY(),block.getZ())) { session.goalAt=new BlockPos(block.getX(),block.getY(),block.getZ()); goal(player,session,course); break; }
            if(!session.goalReached && !session.practice && session.clock.phase()==RunClock.Phase.RUNNING
                    && limitTicks(course,session)>0 && session.clock.elapsed()>=limitTicks(course,session)) { fail(player,session,course,RunRecord.Reason.TIMEOUT); return; }
        }
        showStatus(player,session,course);
    }
    /**
     * After the goal nothing fails: falling out of the lane's box (or into lava) goes back to the goal,
     * and stepping on another course's start in the era moves on to that course. True when handled.
     */
    private boolean afterGoal(Player player,Session session,Course course,Location at,boolean sameWorld) {
        Space space=spaces.get(session.space);
        Region box=space==null ? null : space.laneRegion(session.lane);
        if(!sameWorld || box==null || !box.contains(at.getX(),at.getY(),at.getZ()) || player.isInLava()) { backToGoal(player,session,course); return true; }
        if(!player.isOnGround()) return false;
        Course next=startUnder(player,session,at);
        if(next==null) return false;
        moveOn(player,session,next);
        return true;
    }
    /** Another course of the era whose start (in this lane) is the block the player stands on, or null. */
    private Course startUnder(Player player,Session session,Location at) {
        Era era=session.base==null ? null : spaces.era(session.base);
        if(era==null) return null;
        int x=at.getBlockX(),y=at.getBlockY(),z=at.getBlockZ();
        BlockPos d=session.offset;
        for(String id:era.courses()) {
            if(id.equals(session.course)) continue;
            Course c=courses.get(id);
            if(c==null || c.start()==null || !c.problems().isEmpty()) continue;
            if(!player.hasPermission(ADMIN) && c.status()!=CourseStatus.DONE) continue;
            Spot s=c.start();
            if((int)Math.floor(s.x())+d.x()==x && (int)Math.floor(s.y())+d.y()==y && (int)Math.floor(s.z())+d.z()==z) return c;
        }
        return null;
    }
    /** Walking on into the next course: same lane, its loadout, clock waiting from here. */
    private void moveOn(Player player,Session session,Course next) {
        session.course=next.id(); session.save=null; session.previousSave=null;
        Course laned=course(session);
        if(laned==null) { end(player); Msg.send(player,"play.course-changed"); return; }
        reset(player,session,laned,false);
        session.waitAt=player.getLocation().toVector();
        progress.reach(player.getUniqueId(),next.id());
        Msg.send(player,"play.era.moved",Msg.arg("course",next.name()));
    }
    /** On top of the goal block reached (the start when there is none). */
    private void backToGoal(Player player,Session session,Course course) {
        BlockPos g=session.goalAt; World world=Bukkit.getWorld(course.world());
        if(g==null || world==null) { if(toStart(player,course)) reset(player,session,course,true); return; }
        Location at=player.getLocation();
        if(teleport(player,new Location(world,g.x()+.5,g.y()+1,g.z()+.5,at.getYaw(),at.getPitch()))) { stopMotion(player); player.setFireTicks(0); }
    }
    /**
     * The pressure plates this box presses, as vanilla decides: the plate's touch box
     * (1/16 in from each side, 1/4 block high), not the whole block it stands in.
     */
    static List<BlockPos> platesPressed(World world,BoundingBox box) {
        List<BlockPos> plates=new ArrayList<>(4); int y=(int)Math.floor(box.getMinY());
        if(box.getMinY()>=y+PLATE_HEIGHT) return plates;
        for(int x=(int)Math.floor(box.getMinX());x<=Math.floor(box.getMaxX()-1e-6);x++)
            for(int z=(int)Math.floor(box.getMinZ());z<=Math.floor(box.getMaxZ()-1e-6);z++) {
                if(box.getMinX()>=x+1-PLATE_INSET || box.getMaxX()<=x+PLATE_INSET || box.getMinZ()>=z+1-PLATE_INSET || box.getMaxZ()<=z+PLATE_INSET) continue;
                if(Tag.PRESSURE_PLATES.isTagged(world.getBlockAt(x,y,z).getType())) plates.add(new BlockPos(x,y,z));
            }
        return plates;
    }
    private static final double PLATE_INSET=1/16.0,PLATE_HEIGHT=.25;
    @EventHandler public void serverTick(ServerTickEndEvent event) {
        int now=Bukkit.getCurrentTick();
        for(var entry:List.copyOf(sessions.entrySet())) {
            Session session=entry.getValue(); Player player=Bukkit.getPlayer(entry.getKey()); Course course=course(session);
            if(player==null || course==null || player.isDead()) continue;
            if(session.pendingFail!=null && !session.goalReached) {
                // Damage callbacks only flag the failure; teleporting there can be undone by the rest of that movement.
                RunRecord.Reason reason=session.pendingFail; session.pendingFail=null; fail(player,session,course,reason); continue;
            }
            if(!session.practice && session.clock.phase()==RunClock.Phase.RUNNING && limitTicks(course,session)>0
                    && System.nanoTime()-session.startNanos>limitTicks(course,session)/20.0*WALL_CLOCK_MARGIN*1e9) {
                session.unmeasured=true; fail(player,session,course,RunRecord.Reason.TIMEOUT); continue;
            }
            if(session.goalReached || now-session.lastClientTick<=SILENCE_LIMIT) continue;
            session.unmeasured=true;
            if(now%10==0) showStatus(player,session,course);
        }
    }
    private void showStatus(Player player,Session session,Course course) {
        if(session.unmeasured && !session.goalReached) { Msg.actionBar(player,"play.bar.unmeasured"); return; }
        Component elapsed=time(player,session.clock.elapsed());
        // Practice shows where you are now; the official run shows the furthest reached.
        Component percent=!session.route.usable() ? null : Msg.text(player,"play.bar.percent",Msg.arg("percent",RouteProgress.percent(
                session.route.fraction(session.practice ? session.along : session.furthest),session.goalReached)));
        List<Component> parts=new ArrayList<>();
        if(session.practice) {
            parts.add(Msg.text(player,"play.bar.practice"));
            parts.add(session.goalReached ? Msg.text(player,"play.bar.practice.goal")
                    : switch(session.clock.phase()) {
                        case WAITING -> Msg.text(player,"play.bar.practice.waiting");
                        case RUNNING -> Msg.text(player,"play.bar.practice.running",Msg.arg("time",elapsed));
                        case FINISHED -> Msg.text(player,"play.bar.practice.finished",Msg.arg("time",elapsed));
                    });
            if(percent!=null) parts.add(percent);
            parts.add(Msg.text(player,"play.bar.to-save"));
            parts.add(Msg.text(player,"play.bar.save"));
        } else {
            long limitTicks=limitTicks(course,session);
            Component limit=limitTicks==0 ? Component.empty()
                    : Msg.text(player,"play.bar.limit",Msg.arg("limit",Msg.text(player,"time.seconds",Msg.arg("value",RunClock.seconds(limitTicks)))));
            if(session.goalReached) parts.add(Msg.text(player,"play.bar.goal",Msg.arg("time",elapsed)));
            else if(session.clock.phase()==RunClock.Phase.WAITING) parts.add(Msg.text(player,"play.bar.waiting",Msg.arg("limit",limit)));
            else parts.add(Msg.text(player,"play.bar.time",Msg.arg("time",elapsed),Msg.arg("limit",limit)));
            if(percent!=null && session.clock.phase()!=RunClock.Phase.WAITING) parts.add(percent);
            parts.add(Msg.text(player,"play.bar.retry"));
        }
        player.sendActionBar(bar(player,parts));
    }
    /** Parts joined with the light gray "|" separator. */
    private static Component bar(Player player,List<Component> parts) {
        return Component.join(net.kyori.adventure.text.JoinConfiguration.separator(Msg.text(player,"play.bar.separator")),parts);
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
    /** Courses with placeable cells: placing anywhere else is cancelled and fails the attempt (after the tick). */
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void placeOutside(BlockPlaceEvent event) {
        Session session=sessions.get(event.getPlayer().getUniqueId());
        if(session==null || session.goalReached) return;
        Course course=course(session);
        var block=event.getBlockPlaced();
        if(course==null || course.canPlace(block.getX(),block.getY(),block.getZ())) return;
        event.setCancelled(true);
        session.pendingFail=RunRecord.Reason.PLACE;
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
        Course course=course(session); if(course==null || !course.failLava()) return;
        if(event.getCause()==EntityDamageEvent.DamageCause.LAVA) { event.setCancelled(true); if(!session.goalReached) session.pendingFail=RunRecord.Reason.LAVA; }
        else if(event.getCause()==EntityDamageEvent.DamageCause.FIRE_TICK) { event.setCancelled(true); player.setFireTicks(0); }
    }
    @EventHandler(priority=EventPriority.HIGH)
    public void combust(EntityCombustByBlockEvent event) {
        if(!(event.getEntity() instanceof Player player)) return;
        Session session=sessions.get(player.getUniqueId()); if(session==null) return;
        Course course=course(session); if(course==null || !course.failLava()) return;
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
    /** Fills hotbar slots 4-9 of contents: 5 mode switch, 6 flight (practice), 8 visibility. */
    private void systemItems(Player player,Session session,ItemStack[] contents) {
        for(int slot=Course.COURSE_SLOTS;slot<9;slot++) contents[slot]=null;
        // Official: sugar switches to practice. Practice: redstone switches back.
        contents[MODE_SLOT]=session.practice
                ? systemItem(player,SystemItem.MODE,Material.REDSTONE,"play.item.mode-practice")
                : systemItem(player,SystemItem.MODE,Material.SUGAR,"play.item.mode-official");
        if(session.practice) {
            boolean flight=player.getAllowFlight();
            ItemStack feather=systemItem(player,SystemItem.FLIGHT,Material.FEATHER,flight ? "play.item.flight-on" : "play.item.flight-off");
            feather.editMeta(meta -> meta.setEnchantmentGlintOverride(flight));
            contents[FLIGHT_SLOT]=feather;
        }
        if(visibility!=null) contents[Visibility.SLOT]=visibility.item(player);
    }
    /** Updates only the system slots, in place. */
    private void systemItems(Player player,Session session) {
        PlayerInventory inventory=player.getInventory();
        ItemStack[] contents=new ItemStack[9];
        systemItems(player,session,contents);
        for(int slot=Course.COURSE_SLOTS;slot<9;slot++) Inventories.put(inventory,slot,contents[slot]);
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
        Course course=course(session);
        World world=course==null ? null : Bukkit.getWorld(course.world());
        if(course==null || world==null || course.start()==null) return;
        if(session.goalReached && session.goalAt!=null) {
            BlockPos g=session.goalAt;
            event.setRespawnLocation(new Location(world,g.x()+.5,g.y()+1,g.z()+.5,player.getYaw(),player.getPitch()));
            return;
        }
        Spot s=course.start();
        event.setRespawnLocation(session.practice && session.save!=null ? session.save.at().clone() : new Location(world,s.x(),s.y(),s.z(),s.yaw(),s.pitch()));
        Bukkit.getScheduler().runTask(plugin,() -> {
            if(!player.isOnline() || sessions.get(player.getUniqueId())!=session) return;
            if(session.practice && session.save!=null) load(player,session); else reset(player,session,course,true);
        });
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        Player player=event.getPlayer();
        lanes.dequeue(player.getUniqueId());
        if(active(player)) { keepResume(player); end(player); }
    }
    /** Gives back what they had before a run; then, if they logged out mid-era, back to that course in a free lane (or in line). */
    @EventHandler public void join(PlayerJoinEvent event) {
        Player player=event.getPlayer(); UUID id=player.getUniqueId();
        String resume=progress.resume(id);
        if(returns.get(id)==null && resume==null) return;
        Bukkit.getScheduler().runTask(plugin,() -> {
            if(!player.isOnline() || active(player)) return;
            restore(player);
            if(resume==null) return;
            if(courses.get(resume)==null) { progress.clearResume(id); return; }
            Msg.send(player,"play.era.resume");
            enter(player,resume,false);
            if(active(player)) return;
            if(lanes.waiting(player)) { Location spot=waitingSpot.get(); if(spot!=null) Teleports.to(player,spot); }
            else progress.clearResume(id);
        });
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
