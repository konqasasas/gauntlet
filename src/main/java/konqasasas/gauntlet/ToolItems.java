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
    /** Names and descriptions: message keys editor.tool.<kind>.name/.lore (builder language). */
    enum Kind {
        CHECKPOINT(Material.AMETHYST_SHARD,1),
        SCAFFOLD(Material.POLISHED_ANDESITE,64),
        MODE(Material.SLIME_BALL,1);
        final Material material; final int count;
        Kind(Material material,int count) { this.material=material; this.count=count; }
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
    boolean grant(Player player) {
        PlayerInventory inv=player.getInventory();
        Map<Kind,Integer> first=new EnumMap<>(Kind.class); List<Integer> duplicates=new ArrayList<>();
        for(int slot=0;slot<inv.getSize();slot++) {
            Kind kind=kind(inv.getItem(slot));
            if(kind!=null && first.putIfAbsent(kind,slot)!=null) duplicates.add(slot);
        }
        int empty=0;
        for(int slot=0;slot<36;slot++) if(inv.getItem(slot)==null || inv.getItem(slot).getType().isAir() || duplicates.contains(slot)) empty++;
        if(empty<Kind.values().length-first.size()) return false;
        duplicates.forEach(slot -> inv.setItem(slot,null));
        for(Kind kind:Kind.values()) {
            if(first.containsKey(kind)) inv.setItem(first.get(kind),create(kind));
            else inv.addItem(create(kind));
        }
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
