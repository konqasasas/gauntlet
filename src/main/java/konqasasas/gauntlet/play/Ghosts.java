package konqasasas.gauntlet.play;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.*;
import java.util.function.Predicate;
import konqasasas.gauntlet.course.*;
import konqasasas.gauntlet.display.Sidebar;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.BoundingBox;

/**
 * Ghosts: players in the same era (or course outside the eras) but another lane, shown in your own lane as
 * mannequins (an ordinary entity with the player's skin; cheaper than a skin built
 * from display entities when many players crowd one place).
 *
 * A mannequin takes clicks like a player does (the client aims at an entity in
 * front of a block, whatever its team), so a ghost closer than hide-within to
 * your eyes is hidden from you: it can never stand between you and a block you
 * can reach. Each ghost exists for one viewer only, follows its player every tick,
 * and is never saved.
 *
 * config.yml ghosts: near (only ghosts within this distance), max (nearest first;
 * 0 = no limit), hide-within.
 */
public final class Ghosts implements Listener {
    static final String TAG="gauntlet_ghost";
    public record Settings(double near,int max,double hideWithin) {
        public static Settings load(JavaPlugin plugin) {
            var c=plugin.getConfig();
            return new Settings(Math.max(0,c.getDouble("ghosts.near",64)),Math.max(0,c.getInt("ghosts.max",0)),Math.max(0,c.getDouble("ghosts.hide-within",5)));
        }
    }
    private record Pair(UUID viewer,UUID source) {}
    private static final class Ghost {
        Mannequin entity; boolean hidden; Material held; Pose pose;
    }
    private static final Set<Pose> POSES=Mannequin.validPoses();
    private final JavaPlugin plugin;
    private final Spaces spaces;
    private final Participants participants;
    /** False when the viewer turned other players off (hotbar slot 8): no ghosts either. */
    private final Predicate<Player> wantsPlayers;
    private final Map<Pair,Ghost> ghosts=new HashMap<>();
    private Settings settings;
    private BukkitTask task;

    public Ghosts(JavaPlugin plugin,Spaces spaces,Participants participants,Predicate<Player> wantsPlayers) {
        this.plugin=plugin; this.spaces=spaces; this.participants=participants; this.wantsPlayers=wantsPlayers;
        settings=Settings.load(plugin);
    }
    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        for(World world:Bukkit.getWorlds()) for(Entity e:world.getEntities()) if(e.getScoreboardTags().contains(TAG)) e.remove();
        task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,1,1);
    }
    public void stop() {
        if(task!=null) task.cancel();
        for(var e:ghosts.entrySet()) remove(e.getKey(),e.getValue());
        ghosts.clear();
    }

    private void tick() {
        Map<UUID,Participants.View> views=participants.views();
        Map<Pair,Location> wanted=new HashMap<>();
        double near2=settings.near()*settings.near();
        for(var entry:views.entrySet()) {
            Player viewer=Bukkit.getPlayer(entry.getKey()); Participants.View view=entry.getValue();
            Space space=spaces.get(view.space());
            if(viewer==null || space==null || view.lane()>=space.laneCount() || !wantsPlayers.test(viewer)) continue;
            Location here=viewer.getLocation();
            record Candidate(UUID source,Location at,double distance2) {}
            List<Candidate> candidates=new ArrayList<>();
            for(var other:views.entrySet()) {
                Participants.View theirs=other.getValue();
                if(!theirs.space().equals(view.space()) || theirs.lane()==view.lane() || theirs.lane()>=space.laneCount()) continue;
                Player source=Bukkit.getPlayer(other.getKey());
                if(source==null || source.isDead() || source.getGameMode()==GameMode.SPECTATOR || source.getWorld()!=viewer.getWorld()) continue;
                // Where they are in their lane, moved into the viewer's lane.
                BlockPos d=space.laneOffset(view.lane()).minus(space.laneOffset(theirs.lane()));
                Location at=source.getLocation().add(d.x(),d.y(),d.z());
                double distance2=at.distanceSquared(here);
                if(distance2<=near2) candidates.add(new Candidate(other.getKey(),at,distance2));
            }
            candidates.sort(Comparator.comparingDouble(Candidate::distance2));
            if(settings.max()>0 && candidates.size()>settings.max()) candidates=candidates.subList(0,settings.max());
            for(Candidate c:candidates) wanted.put(new Pair(entry.getKey(),c.source()),c.at());
        }
        for(var it=ghosts.entrySet().iterator();it.hasNext();) {
            var e=it.next();
            if(!wanted.containsKey(e.getKey())) { remove(e.getKey(),e.getValue()); it.remove(); }
        }
        wanted.forEach((pair,at) -> {
            Player viewer=Bukkit.getPlayer(pair.viewer()),source=Bukkit.getPlayer(pair.source());
            if(viewer!=null && source!=null) update(pair,viewer,source,at);
        });
    }
    private void update(Pair pair,Player viewer,Player source,Location at) {
        Ghost ghost=ghosts.get(pair);
        if(ghost!=null && (!ghost.entity.isValid() || ghost.entity.getWorld()!=at.getWorld())) { remove(pair,ghost); ghosts.remove(pair); ghost=null; }
        if(ghost==null) {
            // Spawning needs the chunk; a ghost in an unloaded chunk waits until it is loaded.
            if(!at.getWorld().isChunkLoaded(at.getBlockX()>>4,at.getBlockZ()>>4)) return;
            ghost=new Ghost(); ghost.entity=spawn(source,at); ghost.hidden=true;
            ghosts.put(pair,ghost);
            Team team=viewer.getScoreboard().getTeam(Sidebar.HIDE_TEAM);
            if(team!=null) team.addEntry(ghost.entity.getUniqueId().toString());
        } else {
            Location now=ghost.entity.getLocation();
            if(now.distanceSquared(at)>1e-6) ghost.entity.teleport(at);
        }
        Mannequin m=ghost.entity;
        // Head and look direction (setRotation turns the head too).
        if(m.getYaw()!=at.getYaw() || m.getPitch()!=at.getPitch()) m.setRotation(at.getYaw(),at.getPitch());
        m.setBodyYaw(source.getBodyYaw());
        Pose pose=source.isGliding() ? Pose.FALL_FLYING : source.isSwimming() ? Pose.SWIMMING : source.isSneaking() ? Pose.SNEAKING : Pose.STANDING;
        if(pose!=ghost.pose && POSES.contains(pose)) { m.setPose(pose,true); ghost.pose=pose; }
        ItemStack held=source.getInventory().getItemInMainHand();
        if(held.getType()!=ghost.held) { m.getEquipment().setItemInMainHand(held.clone()); ghost.held=held.getType(); }
        // Never between the viewer and a block they can reach.
        boolean hide=distance(viewer.getEyeLocation(),m.getBoundingBox())<=settings.hideWithin();
        if(hide!=ghost.hidden) {
            if(hide) viewer.hideEntity(plugin,m); else viewer.showEntity(plugin,m);
            ghost.hidden=hide;
        }
    }
    private Mannequin spawn(Player source,Location at) {
        return at.getWorld().spawn(at,Mannequin.class,m -> {
            m.setPersistent(false);
            m.addScoreboardTag(TAG);
            m.setVisibleByDefault(false);
            m.setProfile(ResolvableProfile.resolvableProfile(source.getPlayerProfile()));
            m.setDescription(null);
            m.setImmovable(true);
            m.setGravity(false);
            m.setAI(false);
            m.setInvulnerable(true);
            m.setSilent(true);
            m.setCollidable(false);
        });
    }
    private void remove(Pair pair,Ghost ghost) {
        Player viewer=Bukkit.getPlayer(pair.viewer());
        if(viewer!=null) { Team team=viewer.getScoreboard().getTeam(Sidebar.HIDE_TEAM); if(team!=null) team.removeEntry(ghost.entity.getUniqueId().toString()); }
        ghost.entity.remove();
    }
    /** From the point to the nearest point of the box (0 inside). */
    static double distance(Location eye,BoundingBox box) {
        double dx=Math.max(0,Math.max(box.getMinX()-eye.getX(),eye.getX()-box.getMaxX()));
        double dy=Math.max(0,Math.max(box.getMinY()-eye.getY(),eye.getY()-box.getMaxY()));
        double dz=Math.max(0,Math.max(box.getMinZ()-eye.getZ(),eye.getZ()-box.getMaxZ()));
        return Math.sqrt(dx*dx+dy*dy+dz*dz);
    }
    private boolean isGhost(Entity entity) { return entity.getScoreboardTags().contains(TAG); }

    // ---- A ghost does nothing and takes nothing ----
    @EventHandler(priority=EventPriority.MONITOR)
    public void swing(PlayerAnimationEvent event) {
        if(event.getAnimationType()!=PlayerAnimationType.ARM_SWING) return;
        UUID source=event.getPlayer().getUniqueId();
        ghosts.forEach((pair,ghost) -> { if(pair.source().equals(source) && ghost.entity.isValid()) ghost.entity.swingMainHand(); });
    }
    @EventHandler(priority=EventPriority.LOWEST)
    public void interact(PlayerInteractEntityEvent event) { if(isGhost(event.getRightClicked())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.LOWEST)
    public void damage(EntityDamageEvent event) { if(isGhost(event.getEntity())) event.setCancelled(true); }
    @EventHandler public void entitiesLoad(EntitiesLoadEvent event) {
        // Never saved, but a crash could leave one in a chunk saved earlier.
        for(Entity e:event.getEntities()) if(isGhost(e) && ghosts.values().stream().noneMatch(g -> g.entity.equals(e))) e.remove();
    }
}
