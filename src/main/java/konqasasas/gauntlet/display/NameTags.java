package konqasasas.gauntlet.display;

import java.util.*;
import java.util.function.Predicate;
import konqasasas.gauntlet.data.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.*;

/**
 * Names above heads: a TextDisplay riding each player (the vanilla name tag is
 * hidden by the sidebar's team). Not shown during a course, while invisible, to
 * the player themself, or to viewers who hide other players. Never saved.
 *
 * A player with a passenger cannot change worlds, so the display is taken off
 * before plugin teleports, commands and portals, and put back by the next sync.
 */
public final class NameTags implements Listener {
    static final String TAG="gauntlet_nametag";
    private static final long SYNC_TICKS=10;
    private static final byte SNEAK_OPACITY=(byte)96;
    private final JavaPlugin plugin;
    private final PlayerDisplay display;
    private final PlayerData data;
    private final Predicate<Player> hidden;
    private final Map<UUID,TextDisplay> displays=new HashMap<>();
    private final Map<UUID,Component> texts=new HashMap<>();
    private BukkitTask task;

    /** hidden: players whose name must not be shown (in a course). */
    public NameTags(JavaPlugin plugin,PlayerDisplay display,PlayerData data,Predicate<Player> hidden) {
        this.plugin=plugin; this.display=display; this.data=data; this.hidden=hidden;
    }
    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        for(World world:Bukkit.getWorlds()) for(Entity e:world.getEntities()) if(e.getScoreboardTags().contains(TAG)) e.remove();
        task=Bukkit.getScheduler().runTaskTimer(plugin,this::sync,SYNC_TICKS,SYNC_TICKS);
    }
    public void stop() {
        if(task!=null) task.cancel();
        for(UUID id:List.copyOf(displays.keySet())) remove(id);
    }
    /** Takes the display off now; sync() puts it back if it should be shown. */
    public void detach(Player player) { remove(player.getUniqueId()); }

    private void sync() {
        for(Player player:Bukkit.getOnlinePlayers()) {
            UUID id=player.getUniqueId();
            boolean want=!hidden.test(player) && !InvisibleGear.invisible(player) && player.getGameMode()!=GameMode.SPECTATOR && !player.isDead() && player.isValid();
            TextDisplay current=displays.get(id);
            if(!want) { if(current!=null) remove(id); continue; }
            if(current==null || !current.isValid() || !player.getPassengers().contains(current)) { remove(id); create(player); continue; }
            Component text=display.nametag(player);
            if(!text.equals(texts.get(id))) { current.text(text); texts.put(id,text); }
        }
        displays.keySet().removeIf(id -> {
            if(Bukkit.getPlayer(id)!=null) return false;
            TextDisplay d=displays.get(id); if(d!=null) d.remove(); texts.remove(id); return true;
        });
    }
    private void create(Player player) {
        Component text=display.nametag(player);
        TextDisplay tag=player.getWorld().spawn(player.getLocation(),TextDisplay.class,d -> {
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.setBillboard(Display.Billboard.CENTER);
            d.text(text);
            d.setShadowed(false);
            d.setSeeThrough(false);
            d.setDefaultBackground(true);
            d.setTextOpacity(player.isSneaking() ? SNEAK_OPACITY : (byte)-1);
            // Lift it from the riding point to where the vanilla name tag would be.
            d.setTransformation(new Transformation(new Vector3f(0,.3f,0),new AxisAngle4f(),new Vector3f(1,1,1),new AxisAngle4f()));
        });
        if(!player.addPassenger(tag)) { tag.remove(); return; }
        player.hideEntity(plugin,tag);
        for(Player viewer:Bukkit.getOnlinePlayers())
            if(viewer!=player && !data.get(viewer,Settings.PLAYERS_VISIBLE)) viewer.hideEntity(plugin,tag);
        displays.put(player.getUniqueId(),tag); texts.put(player.getUniqueId(),text);
    }
    private void remove(UUID id) {
        TextDisplay d=displays.remove(id); texts.remove(id);
        if(d!=null) d.remove();
    }
    /** After a viewer changed whether other players are shown. */
    public void refreshViewer(Player viewer) {
        boolean shown=data.get(viewer,Settings.PLAYERS_VISIBLE);
        displays.forEach((owner,d) -> {
            if(owner.equals(viewer.getUniqueId()) || !d.isValid()) return;
            if(shown) viewer.showEntity(plugin,d); else viewer.hideEntity(plugin,d);
        });
    }

    @EventHandler public void sneak(PlayerToggleSneakEvent event) {
        TextDisplay d=displays.get(event.getPlayer().getUniqueId());
        if(d!=null) d.setTextOpacity(event.isSneaking() ? SNEAK_OPACITY : (byte)-1);
    }
    @EventHandler(priority=EventPriority.LOWEST)
    public void command(PlayerCommandPreprocessEvent event) { detach(event.getPlayer()); }
    @EventHandler(priority=EventPriority.LOWEST)
    public void portal(PlayerPortalEvent event) { detach(event.getPlayer()); }
    @EventHandler public void death(org.bukkit.event.entity.PlayerDeathEvent event) { detach(event.getEntity()); }
    @EventHandler public void quit(PlayerQuitEvent event) { detach(event.getPlayer()); }
    @EventHandler public void entitiesLoad(EntitiesLoadEvent event) {
        // Never saved, but a crash could leave one in a chunk saved earlier.
        for(Entity e:event.getEntities()) if(e.getScoreboardTags().contains(TAG) && !displays.containsValue(e)) e.remove();
    }
}
