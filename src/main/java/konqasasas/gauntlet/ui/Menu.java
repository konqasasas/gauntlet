package konqasasas.gauntlet.ui;

import java.util.*;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;

/** Chest GUI whose items are buttons. Items can never be taken out. */
public final class Menu implements InventoryHolder {
    private final Inventory inventory;
    private final Map<Integer,Consumer<InventoryClickEvent>> actions=new HashMap<>();
    public Menu(int rows,String title) { inventory=Bukkit.createInventory(this,rows*9,Component.text(title)); }
    public Menu button(int slot,ItemStack icon,Consumer<InventoryClickEvent> action) {
        inventory.setItem(slot,icon); actions.put(slot,action); return this;
    }
    public Menu label(int slot,ItemStack icon) { inventory.setItem(slot,icon); return this; }
    public void open(Player player) { player.openInventory(inventory); }
    @Override public Inventory getInventory() { return inventory; }

    public static final class Events implements Listener {
        @EventHandler public void click(InventoryClickEvent event) {
            if(!(event.getView().getTopInventory().getHolder(false) instanceof Menu menu)) return;
            // Cancel everything, including shift-clicks from the player's own inventory.
            event.setCancelled(true);
            if(event.getClickedInventory()!=menu.inventory) return;
            var action=menu.actions.get(event.getSlot());
            if(action!=null) action.accept(event);
        }
        @EventHandler public void drag(InventoryDragEvent event) {
            if(event.getView().getTopInventory().getHolder(false) instanceof Menu) event.setCancelled(true);
        }
    }
}
