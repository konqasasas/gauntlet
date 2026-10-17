package konqasasas.gauntlet.course;

import konqasasas.gauntlet.i18n.Msg;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Region selection wand. Shares the tool tag key; ToolItems ignores this value. */
final class CourseWand {
    private static final String TAG="COURSE_WAND";
    private final NamespacedKey key;
    CourseWand(NamespacedKey key) { this.key=key; }
    boolean is(ItemStack item) {
        if(item==null || item.getType()!=Material.BREEZE_ROD) return false;
        return TAG.equals(item.getItemMeta().getPersistentDataContainer().get(key,PersistentDataType.STRING));
    }
    ItemStack create(Player player) {
        ItemStack item=Msg.icon(player,Material.BREEZE_ROD,"editor.wand.item");
        item.editMeta(meta -> meta.getPersistentDataContainer().set(key,PersistentDataType.STRING,TAG));
        return item;
    }
}
