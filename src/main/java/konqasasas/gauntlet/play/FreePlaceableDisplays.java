package konqasasas.gauntlet.play;

import java.util.*;
import java.util.function.Predicate;
import konqasasas.gauntlet.course.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.*;

/**
 * Placeable cells outside every course, shown as the same small lime cubes as a
 * course's (PlaceableDisplays) to builders holding the kit block or the marker,
 * for the cells within VIEW_DISTANCE of them. Never saved.
 */
public final class FreePlaceableDisplays implements Listener {
    static final String TAG="gauntlet_free_placeable";
    private static final long SYNC_TICKS=10;
    private static final double VIEW_DISTANCE=48;
    private static final float SIZE=.25f;
    private record Cell(UUID world,BlockPos pos) {}
    private final JavaPlugin plugin;
    private final FreePlaceableStore store;
    private final Predicate<Player> viewer;
    private final Map<Cell,BlockDisplay> displays=new HashMap<>();
    private BukkitTask task;

    public FreePlaceableDisplays(JavaPlugin plugin,FreePlaceableStore store,Predicate<Player> viewer) {
        this.plugin=plugin; this.store=store; this.viewer=viewer;
    }
    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        for(World world:Bukkit.getWorlds()) for(Entity e:world.getEntities()) if(e.getScoreboardTags().contains(TAG)) e.remove();
        task=Bukkit.getScheduler().runTaskTimer(plugin,this::sync,SYNC_TICKS,SYNC_TICKS);
    }
    public void stop() {
        if(task!=null) task.cancel();
        displays.values().forEach(Entity::remove);
        displays.clear();
    }

    private void sync() {
        Map<UUID,Set<Cell>> seen=new HashMap<>();
        Set<Cell> needed=new HashSet<>();
        for(Player player:Bukkit.getOnlinePlayers()) {
            if(!viewer.test(player)) continue;
            Location at=player.getLocation(); UUID world=at.getWorld().getUID();
            Set<Cell> mine=new HashSet<>();
            for(BlockPos c:store.cells(world)) {
                double dx=c.x()+.5-at.getX(),dy=c.y()+.5-at.getY(),dz=c.z()+.5-at.getZ();
                if(dx*dx+dy*dy+dz*dz<=VIEW_DISTANCE*VIEW_DISTANCE) mine.add(new Cell(world,c));
            }
            seen.put(player.getUniqueId(),mine); needed.addAll(mine);
        }
        displays.entrySet().removeIf(e -> {
            if(needed.contains(e.getKey()) && e.getValue().isValid()) return false;
            e.getValue().remove(); return true;
        });
        for(Cell cell:needed) {
            if(displays.containsKey(cell)) continue;
            World world=Bukkit.getWorld(cell.world());
            if(world==null || !world.isChunkLoaded(cell.pos().x()>>4,cell.pos().z()>>4)) continue;
            displays.put(cell,spawn(world,cell.pos()));
        }
        for(Player player:Bukkit.getOnlinePlayers()) {
            Set<Cell> mine=seen.getOrDefault(player.getUniqueId(),Set.of());
            displays.forEach((cell,d) -> {
                boolean see=mine.contains(cell);
                if(see && !player.canSee(d)) player.showEntity(plugin,d);
                else if(!see && player.canSee(d)) player.hideEntity(plugin,d);
            });
        }
    }
    private BlockDisplay spawn(World world,BlockPos cell) {
        float offset=(1-SIZE)/2;
        return world.spawn(new Location(world,cell.x(),cell.y(),cell.z()),BlockDisplay.class,d -> {
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.setVisibleByDefault(false);
            d.setBlock(Material.LIME_WOOL.createBlockData());
            d.setBrightness(new Display.Brightness(15,15));
            d.setTransformation(new Transformation(new Vector3f(offset,offset,offset),new AxisAngle4f(),new Vector3f(SIZE,SIZE,SIZE),new AxisAngle4f()));
        });
    }
    @EventHandler public void entitiesLoad(EntitiesLoadEvent event) {
        // Never saved, but a crash could leave one in a chunk saved earlier.
        for(Entity e:event.getEntities()) if(e.getScoreboardTags().contains(TAG) && !displays.containsValue(e)) e.remove();
    }
}
