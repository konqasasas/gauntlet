package konqasasas.gauntlet;

import io.papermc.paper.block.BlockPredicate;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemAdventurePredicate;
import java.util.*;
import konqasasas.gauntlet.i18n.Msg;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;

final class ToolItems {
    /**
     * Names and descriptions: message keys editor.tool.<kind>.name/.lore (builder language).
     * The kit is the scaffold block alone (F save, Q back, Shift+F game mode). The
     * checkpoint shard and mode slime ball of the old kit are only recognized, so
     * /gauntlet kit can take them back.
     */
    enum Kind {
        CHECKPOINT(Material.AMETHYST_SHARD,1,true),
        SCAFFOLD(Material.POLISHED_ANDESITE,64,false),
        MODE(Material.SLIME_BALL,1,true);
        final Material material; final int count; final boolean legacy;
        Kind(Material material,int count,boolean legacy) { this.material=material; this.count=count; this.legacy=legacy; }
        String key() { return "editor.tool."+name().toLowerCase(Locale.ROOT); }
    }
    private final NamespacedKey key;
    ToolItems(NamespacedKey key) { this.key=key; }
    Kind kind(ItemStack item) {
        if(item==null || item.getType().isAir()) return null;
        String tag=item.getItemMeta().getPersistentDataContainer().get(key,PersistentDataType.STRING);
        if(tag==null) return null;
        for(Kind kind:Kind.values()) if(tag.equals(kind.name()) && item.getType()==kind.material) return kind;
        return null;
    }
    ItemStack create(Kind kind) {
        ItemStack item=new ItemStack(kind.material,kind.count);
        item.editMeta(meta -> {
            meta.displayName(Msg.text(Msg.CONSOLE,kind.key()+".name"));
            meta.getPersistentDataContainer().set(key,PersistentDataType.STRING,kind.name());
            meta.lore(Msg.lines(Msg.CONSOLE,kind.key()+".lore"));
        });
        if(kind==Kind.SCAFFOLD) item.setData(DataComponentTypes.CAN_PLACE_ON,
                ItemAdventurePredicate.itemAdventurePredicate().addPredicate(BlockPredicate.predicate().build()).build());
        return item;
    }
    /** Gives (or refreshes) the kit block, removing duplicates and the old kit's items. A new block goes to slot 1 when it is free. */
    boolean grant(Player player) {
        PlayerInventory inv=player.getInventory();
        int first=-1; List<Integer> obsolete=new ArrayList<>();
        for(int slot=0;slot<inv.getSize();slot++) {
            Kind kind=kind(inv.getItem(slot));
            if(kind==null) continue;
            if(kind.legacy || first>=0) obsolete.add(slot); else first=slot;
        }
        if(first<0) {
            ItemStack in1=inv.getItem(0);
            if(in1==null || in1.getType().isAir() || obsolete.contains(0)) first=0;
            else for(int slot=0;slot<36 && first<0;slot++) if(inv.getItem(slot)==null || inv.getItem(slot).getType().isAir() || obsolete.contains(slot)) first=slot;
            if(first<0) return false;
        }
        obsolete.forEach(slot -> inv.setItem(slot,null));
        inv.setItem(first,create(Kind.SCAFFOLD));
        player.updateInventory(); return true;
    }
    void refill(Player player) {
        // Replace the stack, not the mirror held by BlockItem's native placement
        // call. That call may consume its old reference after BlockPlaceEvent.
        PlayerInventory inv=player.getInventory();
        for(int slot=0;slot<inv.getSize();slot++) if(kind(inv.getItem(slot))==Kind.SCAFFOLD) {
            ItemStack copy=inv.getItem(slot).clone(); copy.setAmount(64); inv.setItem(slot,copy);
        }
    }
}
