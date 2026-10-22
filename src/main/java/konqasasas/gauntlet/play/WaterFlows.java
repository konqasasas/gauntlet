package konqasasas.gauntlet.play;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;
import konqasasas.gauntlet.course.BlockPos;
import konqasasas.gauntlet.io.AtomicFiles;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Water put out by triggers. Each trigger action's water belongs to one player
 * and is tracked cell by cell: the sources it placed and every air cell its
 * water flowed into. It never flows into anything but air, so removing it means
 * turning the tracked cells that are still water back into air. Nothing else is
 * touched, including water that was there before.
 *
 * When the time is up only the sources go, and the flowing water drains away
 * as vanilla water does; it stays tracked until it is gone, so a reset while it
 * drains still removes it at once. A reset removes everything at once.
 *
 * The tracked cells are journaled to water.json (at most a second behind), so
 * water left by a crash is removed on the next start.
 */
public final class WaterFlows implements Listener {
    /** Water of one action as it was at a practice or kit save: every cell's block data, and the time left (0 = draining). */
    public record Saved(String key,UUID world,Map<BlockPos,String> cells,int remainingTicks) {}
    private record Cell(UUID world,int x,int y,int z) {
        BlockPos pos() { return new BlockPos(x,y,z); }
    }
    private record Journal(int format,List<Cell> cells) {}
    private static final class Flow {
        final UUID owner; final String key; final UUID world;
        final Set<Cell> cells=new LinkedHashSet<>();
        int expiresAt;
        /** Sources removed; drainStarted is the tick it began. */
        boolean draining; int drainStarted;
        Flow(UUID owner,String key,UUID world) { this.owner=owner; this.key=key; this.world=world; }
    }
    private static final int JOURNAL_TICKS=20,DRAIN_CHECK_TICKS=10;
    /** Water still there this long after its sources went (it found another source) is removed at once. */
    private static final int DRAIN_LIMIT_TICKS=600;
    private final JavaPlugin plugin;
    private final Path file;
    private final Gson gson=new GsonBuilder().setPrettyPrinting().create();
    private final List<Flow> flows=new ArrayList<>();
    private final Map<Cell,Flow> byCell=new HashMap<>();
    /** Journaled cells in worlds that are not loaded yet. */
    private final Set<Cell> leftovers=new LinkedHashSet<>();
    private boolean dirty;
    private int lastWrite;
    private BukkitTask task;

    public WaterFlows(JavaPlugin plugin,Path file) { this.plugin=plugin; this.file=file; }
    /** Told of every block this class changes (setType fires no event), for replays. */
    private java.util.function.Consumer<Block> changeListener=b -> {};
    public void onChange(java.util.function.Consumer<Block> listener) { changeListener=listener; }
    public void start() throws IOException {
        if(Files.exists(file)) {
            try {
                Journal saved=gson.fromJson(Files.readString(file),Journal.class);
                if(saved==null || saved.format()!=1 || saved.cells()==null) throw new IllegalArgumentException("Unsupported format");
                saved.cells().stream().filter(Objects::nonNull).forEach(leftovers::add);
            } catch(RuntimeException e) { throw new IOException("Water journal is invalid; preserved: "+file,e); }
        }
        recoverLeftovers();
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,1,1);
    }
    public void stop() {
        if(task!=null) task.cancel();
        for(Flow flow:List.copyOf(flows)) remove(flow);
        writeJournal();
    }

    // ---- Owners ----
    /** Puts out the action's water for this player (replacing that action's earlier water). */
    public void start(Player owner,World world,String key,List<BlockPos> cells,int ticks) {
        Flow old=find(owner.getUniqueId(),key); if(old!=null) remove(old);
        Flow flow=new Flow(owner.getUniqueId(),key,world.getUID());
        flow.expiresAt=Bukkit.getCurrentTick()+ticks;
        flows.add(flow);
        for(BlockPos pos:cells) {
            Block block=world.getBlockAt(pos.x(),pos.y(),pos.z());
            Cell cell=new Cell(world.getUID(),pos.x(),pos.y(),pos.z());
            if(!block.getType().isAir() || byCell.containsKey(cell)) continue;
            track(flow,cell);
            block.setType(Material.WATER); changeListener.accept(block);
        }
    }
    /** Removes all water of this player. */
    public void clear(UUID owner) { for(Flow flow:List.copyOf(flows)) if(flow.owner.equals(owner)) remove(flow); }
    public void clear(Player owner) { clear(owner.getUniqueId()); }
    /** This player's water now, for a save. */
    public List<Saved> snapshot(Player owner) {
        int now=Bukkit.getCurrentTick();
        List<Saved> saved=new ArrayList<>();
        for(Flow flow:flows) {
            if(!flow.owner.equals(owner.getUniqueId())) continue;
            World world=Bukkit.getWorld(flow.world); if(world==null) continue;
            Map<BlockPos,String> cells=new LinkedHashMap<>();
            for(Cell cell:flow.cells) {
                Block block=world.getBlockAt(cell.x(),cell.y(),cell.z());
                if(block.getType()==Material.WATER) cells.put(cell.pos(),block.getBlockData().getAsString());
            }
            if(!cells.isEmpty()) saved.add(new Saved(flow.key,flow.world,cells,flow.draining ? 0 : Math.max(1,flow.expiresAt-now)));
        }
        return List.copyOf(saved);
    }
    /** Makes this player's water what it was at the save (none for an empty list). */
    public void restore(Player owner,List<Saved> saved) {
        clear(owner);
        int now=Bukkit.getCurrentTick();
        for(Saved s:saved) {
            World world=Bukkit.getWorld(s.world()); if(world==null) continue;
            Flow flow=new Flow(owner.getUniqueId(),s.key(),s.world());
            flow.expiresAt=now+s.remainingTicks();
            // Saved while draining: the flowing water is put back and drains again.
            flow.draining=s.remainingTicks()<=0; flow.drainStarted=now;
            flows.add(flow);
            // Sources first, so the flowing cells are fed when their updates run.
            List<Map.Entry<BlockPos,String>> cells=new ArrayList<>(s.cells().entrySet());
            cells.sort(Comparator.comparing(e -> !e.getValue().contains("level=0")));
            for(var entry:cells) {
                BlockPos pos=entry.getKey(); Cell cell=new Cell(s.world(),pos.x(),pos.y(),pos.z());
                Block block=world.getBlockAt(pos.x(),pos.y(),pos.z());
                if(!block.getType().isAir() || byCell.containsKey(cell)) continue;
                track(flow,cell);
                try { block.setBlockData(Bukkit.createBlockData(entry.getValue())); }
                catch(IllegalArgumentException e) { block.setType(Material.WATER); }
                changeListener.accept(block);
            }
        }
    }

    // ---- Cells ----
    /** True when this cell holds trigger water. */
    public boolean tracked(Block block) { return byCell.containsKey(cell(block)); }
    private static Cell cell(Block block) { return new Cell(block.getWorld().getUID(),block.getX(),block.getY(),block.getZ()); }
    private Flow find(UUID owner,String key) {
        for(Flow flow:flows) if(flow.owner.equals(owner) && flow.key.equals(key)) return flow;
        return null;
    }
    private void track(Flow flow,Cell cell) { flow.cells.add(cell); byCell.put(cell,flow); dirty=true; }
    private void remove(Flow flow) {
        flows.remove(flow);
        World world=Bukkit.getWorld(flow.world);
        for(Cell cell:flow.cells) {
            byCell.remove(cell);
            if(world==null) { leftovers.add(cell); continue; }
            Block block=world.getBlockAt(cell.x(),cell.y(),cell.z());
            // No physics: neighbours of the same water are removed in this loop too.
            if(block.getType()==Material.WATER) { block.setType(Material.AIR,false); changeListener.accept(block); }
        }
        dirty=true;
    }
    /**
     * Time is up: only the sources go (with block updates, so the flowing water
     * around them recedes). Any source in the water counts, including one that
     * formed from two of ours.
     */
    private void drain(Flow flow,int now) {
        flow.draining=true; flow.drainStarted=now;
        World world=Bukkit.getWorld(flow.world); if(world==null) return;
        for(Cell cell:flow.cells) {
            Block block=world.getBlockAt(cell.x(),cell.y(),cell.z());
            if(block.getType()==Material.WATER && block.getBlockData() instanceof Levelled water && water.getLevel()==0) { block.setType(Material.AIR); changeListener.accept(block); }
        }
    }
    /** Drained away: stop tracking without touching any block. */
    private void forget(Flow flow) {
        flows.remove(flow);
        for(Cell cell:flow.cells) byCell.remove(cell);
        dirty=true;
    }
    private boolean anyWater(Flow flow) {
        World world=Bukkit.getWorld(flow.world); if(world==null) return true;
        for(Cell cell:flow.cells) {
            // Unloaded chunks: assume water, so the limit removes it later.
            if(!world.isChunkLoaded(cell.x()>>4,cell.z()>>4)) return true;
            if(world.getBlockAt(cell.x(),cell.y(),cell.z()).getType()==Material.WATER) return true;
        }
        return false;
    }
    private void tick() {
        int now=Bukkit.getCurrentTick();
        for(Flow flow:List.copyOf(flows)) {
            if(!flow.draining) { if(flow.expiresAt<=now) drain(flow,now); continue; }
            if(now-flow.drainStarted>=DRAIN_LIMIT_TICKS) remove(flow);
            else if((now-flow.drainStarted)%DRAIN_CHECK_TICKS==0 && !anyWater(flow)) forget(flow);
        }
        if(dirty && now-lastWrite>=JOURNAL_TICKS) writeJournal();
    }
    private void writeJournal() {
        Set<Cell> cells=new LinkedHashSet<>(leftovers); cells.addAll(byCell.keySet());
        try { AtomicFiles.write(file,gson.toJson(new Journal(1,List.copyOf(cells))).getBytes(StandardCharsets.UTF_8)); dirty=false; }
        catch(IOException e) { plugin.getLogger().log(Level.SEVERE,"Cannot write the water journal",e); }
        lastWrite=Bukkit.getCurrentTick();
    }
    /** Water left by a crash: back to air wherever it is still water. */
    private void recoverLeftovers() {
        boolean changed=false;
        for(Iterator<Cell> it=leftovers.iterator();it.hasNext();) {
            Cell cell=it.next(); World world=Bukkit.getWorld(cell.world()); if(world==null) continue;
            Block block=world.getBlockAt(cell.x(),cell.y(),cell.z());
            if(block.getType()==Material.WATER) block.setType(Material.AIR,false);
            it.remove(); changed=true;
        }
        if(changed) { dirty=true; writeJournal(); }
    }

    // ---- Events ----
    /** Tracked water flows only into air, and every cell it reaches is tracked. */
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void flow(BlockFromToEvent event) {
        Flow flow=byCell.get(cell(event.getBlock()));
        if(flow==null || event.getBlock().getType()!=Material.WATER) return;
        Block to=event.getToBlock(); Cell cell=cell(to);
        if(byCell.containsKey(cell)) return;
        if(!to.getType().isAir()) { event.setCancelled(true); return; }
        track(flow,cell);
    }
    /** A block placed into trigger water replaces it: the cell is no longer ours. */
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void placed(BlockPlaceEvent event) { release(event.getBlockPlaced()); }
    private void release(Block block) {
        Flow flow=byCell.remove(cell(block)); if(flow==null) return;
        flow.cells.remove(cell(block)); dirty=true;
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) { clear(event.getPlayer().getUniqueId()); }
    @EventHandler public void worldLoaded(WorldLoadEvent event) { recoverLeftovers(); }
}
