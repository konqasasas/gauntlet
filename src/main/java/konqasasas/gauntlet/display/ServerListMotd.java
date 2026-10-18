package konqasasas.gauntlet.display;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.event.*;

/** The server list description from motd of display.yml; an empty motd keeps server.properties' one. */
public final class ServerListMotd implements Listener {
    private final PlayerDisplay display;
    private final String server;

    public ServerListMotd(PlayerDisplay display,String server) { this.display=display; this.server=server; }

    @EventHandler(priority=EventPriority.NORMAL,ignoreCancelled=true)
    public void ping(PaperServerListPingEvent event) {
        Component motd=display.style().motd(server,event.getNumPlayers(),event.getMaxPlayers());
        if(motd!=null) event.motd(motd);
    }
}
