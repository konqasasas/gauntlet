package konqasasas.gauntlet.replay;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import konqasasas.gauntlet.course.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

/**
 * Records official runs for replays. A recording starts when the run's clock
 * starts, takes a frame every server tick, and is kept only if the run is a
 * clear (finish); anything else throws it away (discard).
 *
 * Blocks: every block changed in the course's box (placed, broken, water flowing,
 * trigger water, falling blocks landing...) is recorded with the data it has at
 * the end of that tick. Things: the entities in the box other than players and
 * this plugin's own displays (ghosts, labels, cubes), every tick.
 */
public final class ReplayRecorder implements Listener {
    /** A run longer than this (ticks) is not recorded. */
    private static final int MAX_FRAMES=20*60*20;
    private static final Set<String> OWN_TAGS=Set.of("gauntlet_ghost","gauntlet_trigger_label","gauntlet_placeable","gauntlet_free_placeable","gauntlet_replay");
    private static final class Recording {
        final String course; final int rev; final World world; final BoundingBox box;
        final List<Replay.Frame> frames=new ArrayList<>();
        final List<Replay.BlockChange> blocks=new ArrayList<>();
        final List<List<Replay.Thing>> things=new ArrayList<>();
        final Map<BlockPos,String> last=new HashMap<>();
        final Set<BlockPos> dirty=new HashSet<>();
        final Map<UUID,Integer> ids=new HashMap<>();
        boolean swing;
        Recording(Course course,World world) {
            this.course=course.id(); rev=course.rev(); this.world=world;
            Region r=course.region(); box=new BoundingBox(r.minX()-1,r.minY()-1,r.minZ()-1,r.maxX()+2,r.maxY()+2,r.maxZ()+2);
        }
    }
    private final JavaPlugin plugin;
    private final ReplayStore store;
    private final Map<UUID,Recording> recordings=new HashMap<>();

    public ReplayRecorder(JavaPlugin plugin,ReplayStore store) { this.plugin=plugin; this.store=store; }
    public void start() { plugin.getServer().getPluginManager().registerEvents(this,plugin); }
    public ReplayStore store() { return store; }

    /** The run's clock started: record it (course as played, in the player's lane). */
    public void begin(Player player,Course course) {
        World world=Bukkit.getWorld(course.world());
        if(world==null || course.region()==null) return;
        recordings.put(player.getUniqueId(),new Recording(course,world));
    }
    public void discard(Player player) { recordings.remove(player.getUniqueId()); }
    /** A clear: saves the recording (off the main thread) under the run's id. */
    public void finish(Player player,UUID run,long ticks) {
        Recording r=recordings.remove(player.getUniqueId());
        if(r==null || r.frames.isEmpty()) return;
        Replay replay=new Replay(run,player.getUniqueId(),r.course,r.rev,ticks,List.copyOf(r.frames),List.copyOf(r.blocks),List.copyOf(r.things));
        Bukkit.getScheduler().runTaskAsynchronously(plugin,() -> {
            try { store.save(replay); }
            catch(IOException|RuntimeException e) { plugin.getLogger().log(Level.SEVERE,"Cannot save the replay of run "+run,e); }
        });
    }

    @EventHandler public void tick(ServerTickEndEvent event) {
        for(var it=recordings.entrySet().iterator();it.hasNext();) {
            var entry=it.next(); Recording r=entry.getValue();
            Player player=Bukkit.getPlayer(entry.getKey());
            if(player==null || r.frames.size()>=MAX_FRAMES) { it.remove(); continue; }
            int tick=r.frames.size();
            Location at=player.getLocation();
            Material held=player.getInventory().getItemInMainHand().getType();
            r.frames.add(new Replay.Frame(at.getX(),at.getY(),at.getZ(),at.getYaw(),at.getPitch(),player.isSneaking(),player.isSprinting(),r.swing,
                    held.isAir() ? "" : held.getKey().asString()));
            r.swing=false;
            for(BlockPos c:r.dirty) {
                String data=r.world.getBlockAt(c.x(),c.y(),c.z()).getBlockData().getAsString();
                if(!data.equals(r.last.get(c))) { r.blocks.add(new Replay.BlockChange(tick,c.x(),c.y(),c.z(),data)); r.last.put(c,data); }
            }
            r.dirty.clear();
            List<Replay.Thing> things=new ArrayList<>();
            for(Entity e:r.world.getNearbyEntities(r.box)) {
                if(e instanceof Player || e instanceof Display || e instanceof Interaction || e.getScoreboardTags().stream().anyMatch(OWN_TAGS::contains)) continue;
                int id=r.ids.computeIfAbsent(e.getUniqueId(),k -> r.ids.size());
                Location l=e.getLocation();
                things.add(new Replay.Thing(id,e.getType().getKey().asString(),l.getX(),l.getY(),l.getZ(),l.getYaw(),
                        e instanceof FallingBlock f ? f.getBlockData().getAsString() : ""));
            }
            r.things.add(things);
        }
    }
    /** A block changed: the recordings whose box holds it take it at the end of the tick. */
    public void changed(Block block) {
        if(recordings.isEmpty()) return;
        for(Recording r:recordings.values())
            if(r.world==block.getWorld() && r.box.contains(block.getX()+.5,block.getY()+.5,block.getZ()+.5)) r.dirty.add(new BlockPos(block.getX(),block.getY(),block.getZ()));
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void place(BlockPlaceEvent e) { changed(e.getBlockPlaced()); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void breakBlock(BlockBreakEvent e) { changed(e.getBlock()); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void flow(BlockFromToEvent e) { changed(e.getToBlock()); changed(e.getBlock()); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void entityBlock(EntityChangeBlockEvent e) { changed(e.getBlock()); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void form(BlockFormEvent e) { changed(e.getBlock()); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void fade(BlockFadeEvent e) { changed(e.getBlock()); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void bucket(PlayerBucketEmptyEvent e) { changed(e.getBlock()); }
    @EventHandler(priority=EventPriority.MONITOR) public void swing(PlayerAnimationEvent e) {
        Recording r=recordings.get(e.getPlayer().getUniqueId());
        if(r!=null && e.getAnimationType()==PlayerAnimationType.ARM_SWING) r.swing=true;
    }
    @EventHandler public void quit(PlayerQuitEvent e) { recordings.remove(e.getPlayer().getUniqueId()); }
}
