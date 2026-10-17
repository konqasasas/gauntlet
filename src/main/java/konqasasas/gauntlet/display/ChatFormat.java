package konqasasas.gauntlet.display;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.event.*;

/** Chat lines as "[ 1 ] [ADMIN] name: message". Translation wraps this renderer. */
public final class ChatFormat implements Listener {
    private final PlayerDisplay display;
    public ChatFormat(PlayerDisplay display) { this.display=display; }

    @EventHandler(priority=EventPriority.NORMAL,ignoreCancelled=true)
    public void chat(AsyncChatEvent event) {
        Component name=display.full(event.getPlayer());
        event.renderer((source,displayName,message,viewer) -> Component.text().append(name).append(Component.text(": ")).append(message).build());
    }
}
