package konqasasas.gauntlet.display;

import java.util.List;
import konqasasas.gauntlet.i18n.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * TAB: header (logo), names with rank badge in rank
 * order, footer (own ping, rotating hint). Header and footer are per viewer
 * (language, ping).
 */
public final class TabList implements Listener {
    private static final long UPDATE_TICKS=40;
    private final JavaPlugin plugin;
    private final PlayerDisplay display;
    private final Component logo;
    private final int pingGood,pingOk;
    private final long hintMillis;
    private BukkitTask task;

    public TabList(JavaPlugin plugin,PlayerDisplay display) {
        this.plugin=plugin; this.display=display;
        FileConfiguration config=plugin.getConfig();
        MiniMessage mini=MiniMessage.miniMessage();
        logo=mini.deserialize("<bold><gradient:#ffb347:#ff5f6d>◆ "+mini.escapeTags(config.getString("server.name","Catena Online"))+" ◆</gradient></bold>");
        pingGood=config.getInt("tab.ping-good",100); pingOk=config.getInt("tab.ping-ok",200);
        hintMillis=Math.max(1,config.getLong("tab.hint-seconds",20))*1000;
    }
    public Component logo() { return logo; }
    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        task=Bukkit.getScheduler().runTaskTimer(plugin,this::update,1,UPDATE_TICKS);
    }
    public void stop() {
        if(task!=null) task.cancel();
        for(Player player:Bukkit.getOnlinePlayers()) { player.sendPlayerListHeaderAndFooter(Component.empty(),Component.empty()); player.playerListName(null); }
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) { Bukkit.getScheduler().runTask(plugin,this::update); }

    private void update() {
        long slot=System.currentTimeMillis()/hintMillis;
        for(Player player:Bukkit.getOnlinePlayers()) {
            Component name=display.full(player);
            if(!name.equals(player.playerListName())) player.playerListName(name);
            int order=display.listOrder(player);
            if(player.getPlayerListOrder()!=order) player.setPlayerListOrder(order);
            Component header=logo;
            Component footer=Component.text().append(Msg.text(player,"tab.ping",Msg.arg("ping",ping(player.getPing())))).build();
            List<Component> hints=Msg.lines(player,"tab.hints");
            if(!hints.isEmpty()) footer=footer.appendNewline().append(hints.get((int)(slot%hints.size())));
            player.sendPlayerListHeaderAndFooter(header,footer);
        }
    }
    private Component ping(int ms) {
        return Component.text(ms+"ms",ms<pingGood ? NamedTextColor.GREEN : ms<pingOk ? NamedTextColor.YELLOW : NamedTextColor.RED);
    }
}
