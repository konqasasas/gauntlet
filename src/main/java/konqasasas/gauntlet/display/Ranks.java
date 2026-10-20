package konqasasas.gauntlet.display;

import java.util.UUID;
import java.util.function.ToIntFunction;
import org.bukkit.entity.Player;

/**
 * Rank numbers: 0 for newcomers (no ring), 1-11 (Anulus) from beating era bosses.
 * Rank colors are in display.yml (DisplayStyle); rank 0 has a look of its own there.
 */
public final class Ranks {
    public static final int MAX=DisplayStyle.RANKS;
    private final ToIntFunction<UUID> stored;
    public Ranks(ToIntFunction<UUID> stored) { this.stored=stored; }
    public int rank(Player player) { return Math.clamp(stored.applyAsInt(player.getUniqueId()),0,MAX); }
}
