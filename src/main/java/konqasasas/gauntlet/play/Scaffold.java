package konqasasas.gauntlet.play;

import java.util.Set;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** The existing placeable scaffold block, provided by the main plugin. */
public interface Scaffold {
    ItemStack create();
    boolean is(ItemStack item);
    /** Removes every scaffold block this player placed. */
    void clear(Player player);
    /** Opaque keys of the scaffold blocks this player has placed so far. */
    Set<Object> placed(Player player);
    /** Removes this player's scaffold blocks except the given ones. */
    void clearExcept(Player player,Set<Object> keep);
    /** Removes only the given scaffold blocks of this player. */
    void remove(Player player,Set<Object> blocks);
    /** The opaque key of a block position, as used by placed(). */
    Object key(Block block);
}
