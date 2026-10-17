package konqasasas.gauntlet.play;

import java.util.*;
import java.util.function.Function;
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
 * Placeable cells shown as small lime wool cubes (BlockDisplay). A course's cubes
 * exist only while someone needs them, and only those players see them: players
 * in that course, and builders holding the wand or marker for it. Never saved.
 */
public final class PlaceableDisplays implements Listener {
    static final String TAG="gauntlet_placeable";
    private static final long SYNC_TICKS=10;
    private static final float SIZE=.25f;
    private final JavaPlugin plugin;
    private final CourseStore courses;
    /** The course whose cells this player should see, or null. */
    private final Function<Player,String> shownCourse;
    /** course id -> cell -> display */
    private final Map<String,Map<BlockPos,BlockDisplay>> displays=new HashMap<>();
    /** player -> course whose cubes they currently see */
    private final Map<UUID,String> viewing=new HashMap<>();
    private BukkitTask task;

    public PlaceableDisplays(JavaPlugin plugin,CourseStore courses,Function<Player,String> shownCourse) {
        this.plugin=plugin; this.courses=courses; this.shownCourse=shownCourse;
    }
    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        for(World world:Bukkit.getWorlds()) for(Entity e:world.getEntities()) if(e.getScoreboardTags().contains(TAG)) e.remove();
        task=Bukkit.getScheduler().runTaskTimer(plugin,this::sync,SYNC_TICKS,SYNC_TICKS);
    }
    public void stop() {
        if(task!=null) task.cancel();
        displays.values().forEach(cells -> cells.values().forEach(Entity::remove));
        displays.clear(); viewing.clear();
    }

    private void sync() {
        Map<String,List<Player>> wanted=new HashMap<>();
        for(Player player:Bukkit.getOnlinePlayers()) {
            String id=shownCourse.apply(player);
            Course course=id==null ? null : courses.get(id);
            if(course!=null && !course.placeable().isEmpty()) wanted.computeIfAbsent(id,k -> new ArrayList<>()).add(player);
        }
        // Courses nobody needs: remove their cubes.
        for(String id:List.copyOf(displays.keySet())) if(!wanted.containsKey(id)) { displays.remove(id).values().forEach(Entity::remove); }
        for(var entry:wanted.entrySet()) update(courses.get(entry.getKey()));
        // Show or hide per viewer.
        viewing.keySet().removeIf(id -> Bukkit.getPlayer(id)==null);
        for(Player player:Bukkit.getOnlinePlayers()) {
            String id=shownCourse.apply(player);
            if(id!=null && !displays.containsKey(id)) id=null;
            String before=viewing.get(player.getUniqueId());
            if(Objects.equals(before,id)) { if(id!=null) showAll(player,id); continue; }
            if(before!=null && displays.containsKey(before)) for(BlockDisplay d:displays.get(before).values()) player.hideEntity(plugin,d);
            if(id==null) viewing.remove(player.getUniqueId());
            else { viewing.put(player.getUniqueId(),id); showAll(player,id); }
        }
    }
    /** Shows every cube of the course (new cubes appear when cells are added). */
    private void showAll(Player player,String id) {
        for(BlockDisplay d:displays.get(id).values()) if(!player.canSee(d)) player.showEntity(plugin,d);
    }
    /** Makes the course's cubes match its cells. */
    private void update(Course course) {
        Map<BlockPos,BlockDisplay> cells=displays.computeIfAbsent(course.id(),k -> new HashMap<>());
        World world=Bukkit.getWorld(course.world());
        Set<BlockPos> target=new HashSet<>(course.placeable());
        cells.entrySet().removeIf(e -> {
            if(target.contains(e.getKey()) && e.getValue().isValid()) return false;
            e.getValue().remove(); return true;
        });
        if(world==null) return;
        for(BlockPos cell:target) if(!cells.containsKey(cell)) cells.put(cell,spawn(world,cell));
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
        for(Entity e:event.getEntities())
            if(e.getScoreboardTags().contains(TAG) && displays.values().stream().noneMatch(cells -> cells.containsValue(e))) e.remove();
    }
}
