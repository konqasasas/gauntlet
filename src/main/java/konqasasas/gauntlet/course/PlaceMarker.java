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
 * Marker for placeable cells. Placing it registers (or unregisters) that cell of
 * the course being edited; the block itself is never placed. Shares the tool tag key.
 */
final class PlaceMarker {
    private static final String TAG="PLACE_MARKER";
    private final NamespacedKey key;
    PlaceMarker(NamespacedKey key) { this.key=key; }
    boolean is(ItemStack item) {
        if(item==null || item.getType()!=Material.LIME_WOOL) return false;
        return TAG.equals(item.getItemMeta().getPersistentDataContainer().get(key,PersistentDataType.STRING));
    }
    ItemStack create(Player player) {
        ItemStack item=Msg.icon(player,Material.LIME_WOOL,"editor.marker.item");
        item.setAmount(64);
        item.editMeta(meta -> meta.getPersistentDataContainer().set(key,PersistentDataType.STRING,TAG));
        // Usable in Adventure too, against any block.
        item.setData(DataComponentTypes.CAN_PLACE_ON,ItemAdventurePredicate.itemAdventurePredicate().addPredicate(BlockPredicate.predicate().build()).build());
        return item;
    }
}
