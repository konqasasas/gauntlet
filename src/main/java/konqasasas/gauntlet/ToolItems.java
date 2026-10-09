package konqasasas.gauntlet;

import io.papermc.paper.block.BlockPredicate;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemAdventurePredicate;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;

final class ToolItems {
    enum Kind {
        CHECKPOINT(Material.AMETHYST_SHARD,1,"チェックポイント"),
        SCAFFOLD(Material.POLISHED_ANDESITE,64,"設置用ブロック"),
        MODE(Material.SLIME_BALL,1,"ゲームモード切替");
        final Material material; final int count; final String name;
        Kind(Material material,int count,String name) { this.material=material; this.count=count; this.name=name; }
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
            meta.displayName(text(kind.name));
            meta.getPersistentDataContainer().set(key,PersistentDataType.STRING,kind.name());
            meta.lore(switch(kind) {
                case CHECKPOINT -> List.of(text("Q：接地中の位置と視点を保存"),text("右クリック：足場を消してCPへ戻る"));
                case SCAFFOLD -> List.of(text("設置しても64個を維持"),text("CPへ戻ると置いた足場を消去"));
                case MODE -> List.of(text("右クリック：Adventure / Creative"));
            });
        });
        if(kind==Kind.SCAFFOLD) item.setData(DataComponentTypes.CAN_PLACE_ON,
                ItemAdventurePredicate.itemAdventurePredicate().addPredicate(BlockPredicate.predicate().build()).build());
        return item;
    }
    private static Component text(String value) { return Component.text(value).decoration(TextDecoration.ITALIC,false); }
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
