package konqasasas.gauntlet.play;

import java.math.BigDecimal;
import java.util.*;
import konqasasas.gauntlet.course.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;

/**
 * Mobs spawned by triggers. They are tagged, not saved with the world, and any
 * tagged mob that is not tracked (left over from a crash) is removed on load.
 */
final class CourseMobs implements Listener {
    static final String TAG="gauntlet_mob";
    /** A saved mob for practice save states. */
    record Saved(EntitySnapshot snapshot,Location at) {}
    private final Set<UUID> tracked=new HashSet<>();

    /** Spawns via /summon so builders can paste their existing SNBT. Empty when it failed. */
    List<UUID> spawn(World world,Spot at,MobSpec spec) {
        String command="execute in "+world.getKey().asString()+" run summon "+spec.type()+" "+plain(at.x())+" "+plain(at.y())+" "+plain(at.z())
                +" "+spec.nbtWithTags(TAG);
        // /summon adds the entity and its passengers synchronously. EntitySpawnEvent is not
        // fired for vehicles such as minecarts, so watch every entity added to the world.
        List<Entity> captured=new ArrayList<>();
        capturing=captured;
        try { Bukkit.dispatchCommand(Bukkit.getConsoleSender(),command); }
        finally { capturing=null; }
        List<UUID> spawned=new ArrayList<>();
        Set<Entity> roots=new LinkedHashSet<>();
        for(Entity entity:captured) {
            Entity root=entity;
            while(root.getVehicle()!=null) root=root.getVehicle();
            roots.add(root);
        }
        for(Entity root:roots) if(root.isValid()) adopt(root,spawned);
        return spawned;
    }
    private List<Entity> capturing;
    @EventHandler(priority=EventPriority.MONITOR)
    public void added(EntityAddToWorldEvent event) { if(capturing!=null) capturing.add(event.getEntity()); }
    List<UUID> respawn(List<Saved> saved) {
        List<UUID> spawned=new ArrayList<>();
        for(Saved s:saved) {
            if(s.at().getWorld()==null) continue;
            Entity entity=s.snapshot().createEntity(s.at());
            if(entity!=null) adopt(entity,spawned);
        }
        return spawned;
    }
    List<Saved> snapshot(Collection<UUID> ids) {
        List<Saved> saved=new ArrayList<>();
        for(UUID id:ids) {
            Entity entity=Bukkit.getEntity(id);
            // Passengers are snapshotted with their vehicle; only top-level mobs are saved.
            if(entity==null || !entity.isValid() || entity.getVehicle()!=null) continue;
            // Non-persistent entities refuse to serialize, so allow it just for the snapshot.
            entity.setPersistent(true);
            EntitySnapshot snapshot;
            try { snapshot=entity.createSnapshot(); } finally { entity.setPersistent(false); }
            if(snapshot!=null) saved.add(new Saved(snapshot,entity.getLocation()));
        }
        return saved;
    }
    boolean anyAlive(Collection<UUID> ids) {
        for(UUID id:ids) { Entity e=Bukkit.getEntity(id); if(e!=null && e.isValid() && !e.isDead()) return true; }
        return false;
    }
    void remove(Collection<UUID> ids) {
        for(UUID id:ids) { Entity e=Bukkit.getEntity(id); if(e!=null) removeTree(e); tracked.remove(id); }
    }
    /** Removes leftovers from before a crash or reload. */
    void sweep() {
        for(World world:Bukkit.getWorlds()) for(Entity e:world.getEntities()) if(e.getScoreboardTags().contains(TAG) && !tracked.contains(e.getUniqueId())) e.remove();
    }
    @EventHandler public void loaded(EntitiesLoadEvent event) {
        for(Entity e:event.getEntities()) if(e.getScoreboardTags().contains(TAG) && !tracked.contains(e.getUniqueId())) e.remove();
    }
    private void adopt(Entity entity,List<UUID> spawned) {
        entity.addScoreboardTag(TAG); entity.setPersistent(false);
        if(entity instanceof Mob mob) mob.setRemoveWhenFarAway(false);
        tracked.add(entity.getUniqueId()); spawned.add(entity.getUniqueId());
        for(Entity passenger:entity.getPassengers()) adopt(passenger,spawned);
    }
    /** Passengers first; each one is lifted 100 blocks before removal, which looks cleaner on clients than removing in place. */
    private void removeTree(Entity entity) {
        for(Entity passenger:entity.getPassengers()) removeTree(passenger);
        tracked.remove(entity.getUniqueId());
        if(entity.isInsideVehicle()) entity.leaveVehicle();
        entity.teleport(entity.getLocation().add(0,100,0));
        entity.remove();
    }
    private static String plain(double value) { return BigDecimal.valueOf(value).toPlainString(); }
}
