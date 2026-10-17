package konqasasas.gauntlet.display;

import konqasasas.gauntlet.data.*;
import konqasasas.gauntlet.i18n.Msg;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Showing or hiding other players (the players.visible setting), switched with
 * the hotbar item kept in slot 8 in the lobby and in courses.
 */
public final class Visibility implements Listener {
    public static final int SLOT=7;
    private final JavaPlugin plugin;
    private final PlayerData data;
    private final NameTags tags;
    private final NamespacedKey key;

    public Visibility(JavaPlugin plugin,PlayerData data,NameTags tags) {
        this.plugin=plugin; this.data=data; this.tags=tags; key=new NamespacedKey(plugin,"visibility");
    }
    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        for(Player player:Bukkit.getOnlinePlayers()) apply(player);
    }
    /** Shows everyone again (plugin disabled). */
    public void stop() {
        for(Player viewer:Bukkit.getOnlinePlayers()) for(Player other:Bukkit.getOnlinePlayers()) if(viewer!=other) viewer.showPlayer(plugin,other);
    }
    public boolean shown(Player viewer) { return data.get(viewer,Settings.PLAYERS_VISIBLE); }
    public ItemStack item(Player viewer) {
        boolean shown=shown(viewer);
        ItemStack item=Msg.icon(viewer,shown ? Material.LIME_DYE : Material.GRAY_DYE,shown ? "hotbar.visibility.on" : "hotbar.visibility.off");
        item.editMeta(meta -> meta.getPersistentDataContainer().set(key,PersistentDataType.BYTE,(byte)1));
        return item;
    }
    public boolean is(ItemStack item) {
        return item!=null && !item.getType().isAir() && item.getItemMeta().getPersistentDataContainer().has(key,PersistentDataType.BYTE);
    }
    /** Applies this viewer's choice to every other player. */
    public void apply(Player viewer) {
        boolean shown=shown(viewer);
        for(Player other:Bukkit.getOnlinePlayers()) {
            if(other==viewer) continue;
            if(shown) viewer.showPlayer(plugin,other); else viewer.hidePlayer(plugin,other);
        }
        tags.refreshViewer(viewer);
    }
    public void toggle(Player viewer) {
        boolean shown=!shown(viewer);
        data.set(viewer.getUniqueId(),Settings.PLAYERS_VISIBLE,shown);
        apply(viewer);
        PlayerInventory inventory=viewer.getInventory();
        for(int slot=0;slot<inventory.getSize();slot++) if(is(inventory.getItem(slot))) inventory.setItem(slot,item(viewer));
        Msg.actionBar(viewer,shown ? "hotbar.visibility.shown" : "hotbar.visibility.hidden");
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) {
        Player joined=event.getPlayer();
        apply(joined);
        for(Player other:Bukkit.getOnlinePlayers()) if(other!=joined && !shown(other)) other.hidePlayer(plugin,joined);
    }
    @EventHandler(priority=EventPriority.LOW)
    public void use(PlayerInteractEvent event) {
        if(!is(event.getItem())) return;
        event.setCancelled(true);
        if(event.getHand()!=EquipmentSlot.HAND) return;
        if(event.getAction()==Action.RIGHT_CLICK_AIR || event.getAction()==Action.RIGHT_CLICK_BLOCK) toggle(event.getPlayer());
    }
}
