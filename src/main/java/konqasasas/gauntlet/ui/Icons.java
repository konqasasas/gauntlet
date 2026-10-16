package konqasasas.gauntlet.ui;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class Icons {
    public static ItemStack icon(Material material,String name,List<String> lore) {
        ItemStack item=new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(text(name));
            meta.lore(lore.stream().map(Icons::text).toList());
        });
        return item;
    }
    public static Component text(String value) { return Component.text(value).decoration(TextDecoration.ITALIC,false); }
    public static void tell(Player player,String message) { player.sendMessage(Component.text("[Gauntlet] "+message)); }
    private Icons() {}
}
