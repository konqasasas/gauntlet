package konqasasas.gauntlet.display;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * How a player's name is shown in chat, TAB and above the head, in the current
 * DisplayStyle (display.yml). The name color belongs to the rank only; the
 * ADMIN tag is a separate part.
 */
public final class PlayerDisplay {
    public static final String ADMIN_PERMISSION="gauntlet.display.admin";
    private final Ranks ranks;
    private volatile DisplayStyle style;

    public PlayerDisplay(Ranks ranks,DisplayStyle style) { this.ranks=ranks; this.style=style; }
    public Ranks ranks() { return ranks; }
    public DisplayStyle style() { return style; }
    /** /gauntlet reload display; TAB, sidebar and name tags pick it up on their next update. */
    public void style(DisplayStyle style) { this.style=style; }
    public boolean admin(Player player) { return player.hasPermission(ADMIN_PERMISSION); }
    public Component badge(Player player) { return style.badge(ranks.rank(player)); }
    public Component name(Player player) { return style.rank(ranks.rank(player),player.getName()); }
    /** "[ 1 ] [ADMIN] name" for chat and TAB (names.full). */
    public Component full(Player player) { return style.full(badge(player),admin(player),name(player)); }
    /** "[ 1 ] name" above the head (names.nametag). */
    public Component nametag(Player player) { return style.nametag(badge(player),name(player)); }
    /** TAB order: ADMIN first, then higher ranks (the client sorts larger values first, then by name). */
    public int listOrder(Player player) { return (admin(player) ? 1000 : 0)+ranks.rank(player); }
}
