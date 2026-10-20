package konqasasas.gauntlet.play;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.logging.Level;
import konqasasas.gauntlet.course.*;
import konqasasas.gauntlet.i18n.Msg;
import konqasasas.gauntlet.io.AtomicFiles;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Lanes: copies of a course placed side by side, so that several players can play
 * the same course without their water and scaffold getting in each other's way.
 *
 * Each player in a course has a lane of their own; when every lane is taken, the
 * next ones wait and get in, in order, as lanes free up. Only lane 1 is edited:
 * its blocks are copied to the other lanes (sync), a slice per tick, and blocks in
 * the other lanes cannot be placed or broken except by the player playing there.
 * Syncs not finished yet are kept in lanes.json and resumed after a restart.
 */
public final class Lanes implements Listener {
    /** Called when a waiting player can get a lane: enters them as if they had asked again. */
    public interface Admit { void admit(Player player,String course,boolean practice); }
    public enum State { FREE,IN_USE,PENDING,COPYING }
    /** Cells looked at, and blocks changed, per tick while copying. */
    private static final int CELLS_PER_TICK=32768,CHANGES_PER_TICK=4096;
    private record SavedFile(int format,Map<String,List<Integer>> pending) {}
    private final JavaPlugin plugin;
    private final CourseStore courses;
    private final Scaffold scaffold;
    private final WaterFlows water;
    private final Path file;
    private final Gson gson=new GsonBuilder().setPrettyPrinting().create();
    private final LaneBook book=new LaneBook();
    /** Who asked for a course's sync, told when its lanes are done. */
    private final Map<String,UUID> requesters=new HashMap<>();
    private Admit admit;
    private Predicate<Player> playing=p -> false;
    private Copy copy;
    private BukkitTask task;

    public Lanes(JavaPlugin plugin,CourseStore courses,Scaffold scaffold,WaterFlows water,Path file) throws IOException {
        this.plugin=plugin; this.courses=courses; this.scaffold=scaffold; this.water=water; this.file=file;
        if(!Files.exists(file)) return;
        try {
            SavedFile saved=gson.fromJson(Files.readString(file),SavedFile.class);
            if(saved==null || saved.format()!=1 || saved.pending()==null) throw new IllegalArgumentException("Unsupported format");
            saved.pending().forEach(book::markPending);
        } catch(RuntimeException e) { throw new IOException("Lane sync file is invalid; preserved: "+file,e); }
    }
    /** Set by Participants, which owns entering a course. */
    void admit(Admit admit,Predicate<Player> playing) { this.admit=admit; this.playing=playing; }
    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,1,1);
    }
    public void stop() {
        if(task!=null) task.cancel();
        // An unfinished copy starts over after the restart.
        if(copy!=null) { book.endCopy(false); copy=null; save(); }
    }

    // ---- Seats ----
    /** The lane given to the player in this course (0 = lane 1), or -1 when none is free. */
    int claim(Course course,UUID player) { return book.claim(course.id(),course.laneCount(),player); }
    void release(UUID player) { book.release(player); }
    /** Queues the player for the course; their place in line, from 1. */
    int enqueue(UUID player,String course,boolean practice) { return book.enqueue(player,course,practice); }
    /** True when the player was waiting. */
    boolean dequeue(UUID player) { return book.dequeue(player)!=null; }
    public boolean waiting(Player player) { return book.waiting(player.getUniqueId())!=null; }
    public int waitingFor(String course) { return book.waitingFor(course); }
    public State state(String course,int lane) {
        if(book.copying(course,lane)) return State.COPYING;
        if(book.inUse(course,lane)) return State.IN_USE;
        return book.pending(course,lane) ? State.PENDING : State.FREE;
    }
    public UUID occupant(String course,int lane) { return book.occupant(course,lane); }
    /** How far the current copy of this lane is, 0-100 (0 when it is not being copied). */
    public int percent(String course,int lane) { return copy!=null && book.copying(course,lane) ? copy.percent() : 0; }

    // ---- Sync ----
    /** Copies lane 1 to these lanes (lanes in use are copied after their player leaves). */
    public void sync(Course course,Collection<Integer> lanes,Player requester) {
        book.markPending(course.id(),lanes);
        if(requester!=null) requesters.put(course.id(),requester.getUniqueId());
        save();
    }
    /** After lanes were removed from the course. */
    public void trim(Course course) {
        if(copy!=null && copy.course.equals(course.id()) && copy.lane>=course.laneCount()) { book.endCopy(true); copy=null; }
        book.trim(course.id(),course.laneCount()); save();
    }
    private void save() {
        Map<String,List<Integer>> pending=new TreeMap<>();
        book.pending().forEach((course,lanes) -> pending.put(course,lanes.stream().sorted().toList()));
        try { AtomicFiles.write(file,gson.toJson(new SavedFile(1,pending)).getBytes(StandardCharsets.UTF_8)); }
        catch(IOException e) { plugin.getLogger().log(Level.SEVERE,"Cannot save lane sync state",e); }
    }

    private void tick() {
        copySlice();
        admitWaiting();
        if(Bukkit.getCurrentTick()%20==0) for(var entry:book.queue()) {
            Player player=Bukkit.getPlayer(entry.getKey());
            if(player==null) { book.dequeue(entry.getKey()); continue; }
            if(playing.test(player)) continue;
            Course course=courses.get(entry.getValue().course());
            Msg.actionBar(player,"play.lane.waiting-bar",Msg.arg("course",course==null ? entry.getValue().course() : course.name()),
                    Msg.arg("position",book.position(entry.getKey())));
        }
    }
    private void admitWaiting() {
        if(admit==null) return;
        for(var entry:book.queue()) {
            Player player=Bukkit.getPlayer(entry.getKey());
            Course course=courses.get(entry.getValue().course());
            if(player==null || course==null) { book.dequeue(entry.getKey()); continue; }
            // First come first: someone further back never jumps ahead for the same course.
            if(book.position(entry.getKey())!=1 || !book.hasFree(course.id(),course.laneCount())) continue;
            book.dequeue(entry.getKey());
            admit.admit(player,course.id(),entry.getValue().practice());
        }
    }
    private void copySlice() {
        if(copy==null) {
            LaneBook.Seat next=book.nextSync(); if(next==null) return;
            Course course=courses.get(next.course());
            World world=course==null ? null : Bukkit.getWorld(course.world());
            if(course==null || course.region()==null || next.lane()>=course.laneCount()) { book.startCopy(next); book.endCopy(true); save(); return; }
            // Wait for the world (it may be loaded later).
            if(world==null) return;
            book.startCopy(next);
            copy=new Copy(course,next.lane(),world);
        }
        Course now=courses.get(copy.course);
        if(now==null || now.laneCount()<=copy.lane || !Objects.equals(now.region(),copy.from) || !now.laneOffset(copy.lane).equals(copy.offset)) {
            // The course moved or lost the lane while copying: start again from the new definition.
            book.endCopy(now!=null && now.laneCount()>copy.lane ? false : true); copy=null; save(); return;
        }
        if(!copy.step()) return;
        String course=copy.course; int lane=copy.lane;
        book.endCopy(true); copy=null; save();
        plugin.getLogger().info("Lane "+(lane+1)+" of "+course+" synced");
        UUID requester=requesters.get(course);
        if(requester!=null && book.pending().get(course)==null) {
            requesters.remove(course);
            Player player=Bukkit.getPlayer(requester);
            if(player!=null) Msg.send(player,"editor.lanes.synced",Msg.arg("name",now.name()));
        }
    }
    /** Copying lane 1's region to one lane, x then z then y. */
    private final class Copy {
        final String course; final int lane; final World world; final Region from; final BlockPos offset;
        int x,y,z; long done;
        Copy(Course course,int lane,World world) {
            this.course=course.id(); this.lane=lane; this.world=world; from=course.region(); offset=course.laneOffset(lane);
            x=from.minX(); y=from.minY(); z=from.minZ();
        }
        int percent() { return (int)Math.min(99,done*100/Math.max(1,from.volume())); }
        /** True when finished. */
        boolean step() {
            int cells=0,changes=0;
            while(cells<CELLS_PER_TICK && changes<CHANGES_PER_TICK) {
                if(x>from.maxX()) return true;
                if(copyCell(world.getBlockAt(x,y,z),world.getBlockAt(x+offset.x(),y+offset.y(),z+offset.z()))) changes++;
                cells++; done++;
                if(++y>from.maxY()) { y=from.minY(); if(++z>from.maxZ()) { z=from.minZ(); x++; } }
            }
            return false;
        }
    }
    /** True when the target changed. Scaffold and trigger water in lane 1 are copied as what is under them. */
    private boolean copyCell(Block source,Block target) {
        // Never overwrite someone's scaffold or trigger water: their cleanup must still find it.
        if(scaffold.before(target)!=null || water.tracked(target)) return false;
        String before=scaffold.before(source);
        BlockData data=before!=null ? Bukkit.createBlockData(before) : water.tracked(source) ? Material.AIR.createBlockData() : null;
        if(data==null && source.getState(false) instanceof TileState) {
            source.getState().copy(target.getLocation()).update(true,false);
            return true;
        }
        if(data==null) data=source.getBlockData();
        if(target.getBlockData().equals(data) && !(target.getState(false) instanceof TileState)) return false;
        target.setBlockData(data,false);
        return true;
    }

    // ---- Only lane 1 is edited ----
    /** The course and lane (1 or more; lane 1 is 0) whose copy holds this block, or null. */
    private LaneBook.Seat copyAt(Block block) {
        UUID world=block.getWorld().getUID();
        for(Course course:courses.all()) {
            if(course.lanes().isEmpty() || !course.world().equals(world)) continue;
            int lane=course.laneAt(block.getX(),block.getY(),block.getZ());
            if(lane>0) return new LaneBook.Seat(course.id(),lane);
        }
        return null;
    }
    /** Changing a block in lane 2 or later: only the player playing that lane may (their scaffold). */
    private boolean locked(Player player,Block block) {
        LaneBook.Seat seat=copyAt(block);
        if(seat==null || seat.equals(book.seat(player.getUniqueId()))) return false;
        Msg.actionBar(player,"editor.lanes.locked",Msg.arg("lane",seat.lane()+1));
        return true;
    }
    @EventHandler(priority=EventPriority.LOW,ignoreCancelled=true)
    public void place(BlockPlaceEvent event) { if(locked(event.getPlayer(),event.getBlockPlaced())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.LOW,ignoreCancelled=true)
    public void breakBlock(BlockBreakEvent event) { if(locked(event.getPlayer(),event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.LOW,ignoreCancelled=true)
    public void emptyBucket(PlayerBucketEmptyEvent event) { if(locked(event.getPlayer(),event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.LOW,ignoreCancelled=true)
    public void fillBucket(PlayerBucketFillEvent event) { if(locked(event.getPlayer(),event.getBlock())) event.setCancelled(true); }
    @EventHandler public void quit(PlayerQuitEvent event) { book.dequeue(event.getPlayer().getUniqueId()); }
}
