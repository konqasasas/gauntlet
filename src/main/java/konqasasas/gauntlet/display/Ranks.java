package konqasasas.gauntlet.display;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

/**
 * Rank numbers and rank colors. PLACEHOLDER until the rank design and progression
 * exist: everyone is rank 1, and the 13 styles below are provisional (solid colors
 * for low ranks, two-color gradients in the middle, multi-color gradients at the top).
 */
public final class Ranks {
    public static final int MAX=13;
    private static final String[] STYLES={
            "<color:#d9d9d9>","<color:#8bd36b>","<color:#3fbf7f>","<color:#4fc3f7>","<color:#4f7dff>","<color:#b07cff>",
            "<gradient:#ff9a5a:#ff4f6a>","<gradient:#ffe06b:#ff9620>","<gradient:#5ee7ff:#7c5cff>","<gradient:#ff7eb6:#8a5cff>",
            "<gradient:#ff5f5f:#ffd84d:#5dffb0>","<gradient:#4dc9ff:#d36bff:#ff5fa2>","<gradient:#ffffff:#ffd54a:#ff7a1a:#ffd54a:#ffffff>"};
    private static final MiniMessage MINI=MiniMessage.miniMessage();

    /** PLACEHOLDER: rank progression does not exist yet. */
    public int rank(Player player) { return 1; }
    /** Text in the rank's color or gradient. */
    public Component styled(int rank,String text) {
        return MINI.deserialize(STYLES[Math.clamp(rank,1,MAX)-1]+MINI.escapeTags(text));
    }
    /** "[ 1 ]" for one digit, "[13]" for two. */
    public static String badgeText(int rank) { return rank<10 ? "[ "+rank+" ]" : "["+rank+"]"; }
}
