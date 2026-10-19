package konqasasas.gauntlet.play;

import java.util.*;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Text above trigger plates (TextDisplay), so builders can tell many triggers
 * apart. A label exists only while someone should see it, and only those
 * builders see it. Never saved.
 */
public final class TriggerLabels implements Listener {
    /** key identifies the plate; the first line is drawn brighter when there are two. */
    public record Label(String key,UUID world,double x,double y,double z,List<String> lines) {}
    static final String TAG="gauntlet_trigger_label";
    private static final long SYNC_TICKS=10;
    private final JavaPlugin plugin;
    private final Function<Player,List<Label>> wanted;
    private final Map<String,TextDisplay> displays=new HashMap<>();
    private final Map<String,Label> shown=new HashMap<>();
    private BukkitTask task;

    public TriggerLabels(JavaPlugin plugin,Function<Player,List<Label>> wanted) { this.plugin=plugin; this.wanted=wanted; }
    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        for(World world:Bukkit.getWorlds()) for(Entity e:world.getEntities()) if(e.getScoreboardTags().contains(TAG)) e.remove();
        task=Bukkit.getScheduler().runTaskTimer(plugin,this::sync,SYNC_TICKS,SYNC_TICKS);
    }
    public void stop() {
        if(task!=null) task.cancel();
        displays.values().forEach(Entity::remove);
        displays.clear(); shown.clear();
    }

    private void sync() {
        Map<UUID,Set<String>> viewers=new HashMap<>();
        Map<String,Label> needed=new HashMap<>();
        for(Player player:Bukkit.getOnlinePlayers()) {
            Set<String> keys=new HashSet<>();
            for(Label label:wanted.apply(player)) { needed.putIfAbsent(label.key(),label); keys.add(label.key()); }
            viewers.put(player.getUniqueId(),keys);
        }
        for(String key:List.copyOf(displays.keySet())) {
            TextDisplay d=displays.get(key);
            Label label=needed.get(key);
            if(label!=null && d.isValid() && label.equals(shown.get(key))) continue;
            if(label!=null && d.isValid() && samePlace(label,shown.get(key))) { d.text(text(label)); shown.put(key,label); continue; }
            d.remove(); displays.remove(key); shown.remove(key);
        }
        for(Label label:needed.values()) {
            if(displays.containsKey(label.key())) continue;
            World world=Bukkit.getWorld(label.world());
            if(world==null || !world.isChunkLoaded((int)Math.floor(label.x())>>4,(int)Math.floor(label.z())>>4)) continue;
            displays.put(label.key(),spawn(world,label)); shown.put(label.key(),label);
        }
        for(Player player:Bukkit.getOnlinePlayers()) {
            Set<String> keys=viewers.getOrDefault(player.getUniqueId(),Set.of());
            displays.forEach((key,d) -> {
                boolean see=keys.contains(key);
                if(see && !player.canSee(d)) player.showEntity(plugin,d);
                else if(!see && player.canSee(d)) player.hideEntity(plugin,d);
            });
        }
    }
    private static boolean samePlace(Label a,Label b) {
        return b!=null && a.world().equals(b.world()) && a.x()==b.x() && a.y()==b.y() && a.z()==b.z();
    }
    private static Component text(Label label) {
        List<Component> lines=new ArrayList<>();
        for(int i=0;i<label.lines().size();i++)
            lines.add(Component.text(label.lines().get(i),i==0 && label.lines().size()>1 ? NamedTextColor.WHITE : NamedTextColor.GRAY));
        return Component.join(JoinConfiguration.newlines(),lines);
    }
    private TextDisplay spawn(World world,Label label) {
        return world.spawn(new Location(world,label.x(),label.y(),label.z()),TextDisplay.class,d -> {
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.setVisibleByDefault(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.setBrightness(new Display.Brightness(15,15));
            d.setViewRange(.5f);
            d.text(text(label));
        });
    }
    @EventHandler public void entitiesLoad(EntitiesLoadEvent event) {
        // Never saved, but a crash could leave one in a chunk saved earlier.
        for(Entity e:event.getEntities()) if(e.getScoreboardTags().contains(TAG) && !displays.containsValue(e)) e.remove();
    }
}
