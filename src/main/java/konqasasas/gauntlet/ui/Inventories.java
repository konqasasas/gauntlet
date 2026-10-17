package konqasasas.gauntlet.ui;

import org.bukkit.inventory.*;

/**
 * Replacing inventory contents without the "item received" bounce. Paper sends
 * each slot change at once, and the client bounces a hotbar slot that goes from
 * empty to filled (or gains items). So never clear first: only changed slots are
 * written, directly from the old item to the new one, like /item replace.
 */
public final class Inventories {
    /** Writes the slot only when it differs. null = empty. */
    public static void put(PlayerInventory inventory,int slot,ItemStack item) {
        ItemStack current=inventory.getItem(slot);
        if(same(current,item)) return;
        inventory.setItem(slot,item);
    }
    /** Makes the whole inventory (storage, armor, off hand) equal to contents; missing entries are empty. */
    public static void apply(PlayerInventory inventory,ItemStack[] contents) {
        for(int slot=0;slot<inventory.getSize();slot++) put(inventory,slot,slot<contents.length ? contents[slot] : null);
    }
    static boolean same(ItemStack a,ItemStack b) {
        boolean emptyA=a==null || a.getType().isAir() || a.getAmount()<=0,emptyB=b==null || b.getType().isAir() || b.getAmount()<=0;
        if(emptyA || emptyB) return emptyA && emptyB;
        return a.equals(b);
    }
    private Inventories() {}
}
