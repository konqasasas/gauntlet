package konqasasas.gauntlet.course;

import io.papermc.paper.block.BlockPredicate;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemAdventurePredicate;
import konqasasas.gauntlet.i18n.Msg;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * A marker block for registering cells: placing it registers (or unregisters)
 * that cell, and the block itself is never placed. Placeable cells use lime
 * wool, water cells blue wool. Shares the tool tag key; names come from the
 * message key's .name/.lore.
 */
final class PlaceMarker {
    private final NamespacedKey key;
    private final String tag;
    private final Material material;
    private final String messageKey;
    PlaceMarker(NamespacedKey key,String tag,Material material,String messageKey) {
        this.key=key; this.tag=tag; this.material=material; this.messageKey=messageKey;
    }
    boolean is(ItemStack item) {
        if(item==null || item.getType()!=material) return false;
        return tag.equals(item.getItemMeta().getPersistentDataContainer().get(key,PersistentDataType.STRING));
    }
    ItemStack create(Player player) {
        ItemStack item=Msg.icon(player,material,messageKey);
        item.setAmount(64);
        item.editMeta(meta -> meta.getPersistentDataContainer().set(key,PersistentDataType.STRING,tag));
        // Usable in Adventure too, against any block.
        item.setData(DataComponentTypes.CAN_PLACE_ON,ItemAdventurePredicate.itemAdventurePredicate().addPredicate(BlockPredicate.predicate().build()).build());
        return item;
    }
}
