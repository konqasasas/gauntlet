package konqasasas.gauntlet.pack;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.logging.Level;
import konqasasas.gauntlet.i18n.Msg;
import net.kyori.adventure.resource.*;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Sends the resource packs (resource-packs.files of config.yml: the menu's pack and BetterModel's
 * build) when a player joins, as separate packs, so neither has to be merged into the other. The
 * files are published by hash into publish-dir, which the web server serves at base-url. A changed
 * file (an exported menu, a rebuilt BetterModel pack) is noticed within check-seconds and sent again
 * to everyone online, so updates need no restart. Without publish-dir (a test server) nothing is sent.
 * The menu asks menuState() so it opens only after its pack has loaded (dialog-editor/docs/plugin-handoff.md 5).
 */
public final class ResourcePacks implements Listener {
    private static final Duration GRACE=Duration.ofHours(1);
    private final JavaPlugin plugin;
    private final boolean enabled,required;
    private final Path dir;
    private final URI base;
    private final List<String> files;
    private final String menuFile;
    private final PackStatus status=new PackStatus();
    private final long checkTicks;
    private PackPublisher publisher;
    private volatile List<PackPublisher.Published> current=List.of();
    private BukkitTask task;

    public ResourcePacks(JavaPlugin plugin) {
        this.plugin=plugin;
        FileConfiguration config=plugin.getConfig();
        enabled=config.getBoolean("resource-packs.enabled",true);
        required=config.getBoolean("resource-packs.required",false);
        dir=Path.of(config.getString("resource-packs.publish-dir","/var/lib/gauntlet-packs"));
        base=URI.create(config.getString("resource-packs.base-url","https://catenaonline.net/packs/"));
        files=List.copyOf(config.getStringList("resource-packs.files"));
        menuFile=config.getString("resource-packs.menu-file","plugins/ParkourGauntlet/menu/resourcepack.zip");
        checkTicks=Math.max(1,config.getLong("resource-packs.check-seconds",10))*20;
    }

    public void start() {
        if(!enabled) return;
        if(!Files.isDirectory(dir)) {
            plugin.getLogger().info("Resource packs are not sent: "+dir+" does not exist (normal on a test server)");
            return;
        }
        publisher=new PackPublisher(Bukkit.getWorldContainer().toPath(),dir,base);
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        task=Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,() -> check(false),1,checkTicks);
    }
    public void stop() { if(task!=null) task.cancel(); }
    /** /gauntlet reload packs: check the files now and send them to everyone again. */
    public boolean reload() {
        if(publisher==null) return false;
        Bukkit.getScheduler().runTaskAsynchronously(plugin,() -> check(true));
        return true;
    }

    private void check(boolean resend) {
        try {
            List<PackPublisher.Published> next=publisher.refresh(files);
            boolean changed=!next.equals(current);
            if(changed) {
                current=next;
                plugin.getLogger().info("Resource packs: "+(next.isEmpty() ? "none" : String.join(", ",next.stream().map(p -> p.source()+" "+p.sha1()).toList())));
            }
            publisher.prune(next,GRACE,Instant.now());
            if(changed || resend) Bukkit.getScheduler().runTask(plugin,() -> Bukkit.getOnlinePlayers().forEach(this::send));
        } catch(IOException|RuntimeException e) {
            plugin.getLogger().log(Level.WARNING,"Cannot publish resource packs",e);
        }
    }

    /** Whether the menu's pack has loaded for this player; READY when it is not sent at all. */
    public PackStatus.State menuState(Player player) {
        if(publisher==null) return PackStatus.State.READY;
        for(PackPublisher.Published p:current) if(p.source().equals(menuFile)) return status.state(player.getUniqueId(),p.id());
        return PackStatus.State.READY;
    }

    private void send(Player player) {
        List<PackPublisher.Published> packs=current;
        if(packs.isEmpty()) return;
        status.sent(player.getUniqueId(),packs.stream().map(PackPublisher.Published::id).toList());
        player.sendResourcePacks(ResourcePackRequest.resourcePackRequest()
                .packs(packs.stream().map(p -> ResourcePackInfo.resourcePackInfo(p.id(),p.uri(),p.sha1())).toList())
                .replace(true)
                .required(required)
                .prompt(Msg.text(player,"resourcepack.prompt")));
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) { send(event.getPlayer()); }

    @EventHandler(priority=EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) { status.forget(event.getPlayer().getUniqueId()); }

    @EventHandler(priority=EventPriority.MONITOR)
    public void status(PlayerResourcePackStatusEvent event) {
        try { status.report(event.getPlayer().getUniqueId(),event.getID(),PackStatus.Report.valueOf(event.getStatus().name())); }
        catch(IllegalArgumentException e) { plugin.getLogger().warning("Unknown resource pack status "+event.getStatus()); }
        switch(event.getStatus()) {
            case FAILED_DOWNLOAD,INVALID_URL,FAILED_RELOAD,DISCARDED ->
                    plugin.getLogger().warning("Resource pack "+event.getID()+" "+event.getStatus()+" for "+event.getPlayer().getName());
            case DECLINED -> plugin.getLogger().info(event.getPlayer().getName()+" declined resource pack "+event.getID());
            default -> {}
        }
    }
}
