package konqasasas.gauntlet.course;

import java.util.List;
import konqasasas.gauntlet.ui.Icons;
import org.bukkit.*;
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
    ItemStack create() {
        ItemStack item=Icons.icon(Material.BREEZE_ROD,"範囲指定の杖",List.of("左クリック：1点目","右クリック：2点目","手に持つと範囲とスタートを表示"));
        item.editMeta(meta -> meta.getPersistentDataContainer().set(key,PersistentDataType.STRING,TAG));
        return item;
    }
}
