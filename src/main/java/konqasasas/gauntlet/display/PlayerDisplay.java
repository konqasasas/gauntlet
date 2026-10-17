package konqasasas.gauntlet.display;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;
import org.bukkit.entity.Player;

/**
 * How a player's name is shown in chat, TAB and above the head. The name color
 * belongs to the rank only; the ADMIN tag is a separate part.
 */
public final class PlayerDisplay {
    public static final String ADMIN_PERMISSION="gauntlet.display.admin";
    private static final Component ADMIN_TAG=Component.text("[ADMIN]",NamedTextColor.RED,TextDecoration.BOLD);
    private final Ranks ranks;

    public PlayerDisplay(Ranks ranks) { this.ranks=ranks; }
    public Ranks ranks() { return ranks; }
    public boolean admin(Player player) { return player.hasPermission(ADMIN_PERMISSION); }
    public Component badge(Player player) { int rank=ranks.rank(player); return ranks.styled(rank,Ranks.badgeText(rank)); }
    public Component name(Player player) { return ranks.styled(ranks.rank(player),player.getName()); }
    /** "[ 1 ] [ADMIN] name" for chat and TAB. */
    public Component full(Player player) {
        Component out=badge(player).append(Component.space());
        if(admin(player)) out=out.append(ADMIN_TAG).append(Component.space());
        return out.append(name(player));
    }
    /** "[ 1 ] name" above the head. */
    public Component nametag(Player player) { return badge(player).append(Component.space()).append(name(player)); }
    /** TAB order: ADMIN first, then higher ranks (the client sorts larger values first, then by name). */
    public int listOrder(Player player) { return (admin(player) ? 1000 : 0)+ranks.rank(player); }
}
