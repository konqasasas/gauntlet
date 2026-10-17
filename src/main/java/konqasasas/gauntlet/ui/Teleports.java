package konqasasas.gauntlet.ui;

import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Plugin teleports. A player carrying a passenger (the name tag display) cannot
 * change worlds, so the passenger is taken off first; it comes back by itself.
 */
public final class Teleports {
    private static volatile Consumer<Player> beforeTeleport=p -> {};
    public static void beforeTeleport(Consumer<Player> hook) { beforeTeleport=hook; }
    public static void prepare(Player player) { beforeTeleport.accept(player); }
    public static boolean to(Player player,Location target) {
        prepare(player);
        return player.teleport(target,PlayerTeleportEvent.TeleportCause.PLUGIN);
    }
    private Teleports() {}
}
