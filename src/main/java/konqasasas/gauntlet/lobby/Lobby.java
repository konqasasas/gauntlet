package konqasasas.gauntlet.lobby;

import java.util.*;
import java.util.function.Predicate;
import konqasasas.gauntlet.display.Visibility;
import konqasasas.gauntlet.i18n.Msg;
import konqasasas.gauntlet.play.Scaffold;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * The lobby world (config lobby.world; empty = off). Adventure/Survival players
 * there outside a course get the lobby hotbar: 1 placeable block, 8 player
 * visibility. Placed blocks disappear after lobby.block-seconds. The inventory
 * is locked like in a course.
 */
public final class Lobby implements Listener {
    private static final int BLOCK_SLOT=0;
    private static final long TICKS=10;
    private final JavaPlugin plugin;
    private final Scaffold scaffold;
    private final Visibility visibility;
    private final Predicate<Player> inCourse;
    private final String world;
    private final long blockMillis;
    /** Placed lobby blocks: owner -> block key -> expiry (System.currentTimeMillis). */
    private final Map<UUID,Map<Object,Long>> blocks=new HashMap<>();
    private BukkitTask task;

    public Lobby(JavaPlugin plugin,Scaffold scaffold,Visibility visibility,Predicate<Player> inCourse) {
        this.plugin=plugin; this.scaffold=scaffold; this.visibility=visibility; this.inCourse=inCourse;
        world=plugin.getConfig().getString("lobby.world","").strip();
        blockMillis=Math.max(1,plugin.getConfig().getLong("lobby.block-seconds",10))*1000;
    }
    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        if(world.isEmpty()) return;
        if(Bukkit.getWorld(world)==null) plugin.getLogger().warning("lobby.world '"+world+"' is not loaded; the lobby hotbar is off until it is");
        task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,TICKS,TICKS);
    }
    public void stop() { if(task!=null) task.cancel(); blocks.clear(); }
    public boolean enabled() { return !world.isEmpty(); }
    /** In the lobby world, outside a course, in Adventure or Survival. */
    public boolean managed(Player player) {
        if(world.isEmpty() || !player.getWorld().getName().equals(world) || inCourse.test(player)) return false;
        return player.getGameMode()==GameMode.ADVENTURE || player.getGameMode()==GameMode.SURVIVAL;
    }

    private void tick() {
        long now=System.currentTimeMillis();
        for(Player player:Bukkit.getOnlinePlayers()) if(managed(player) && !laidOut(player)) layOut(player);
        for(var owner:List.copyOf(blocks.entrySet())) {
            Player player=Bukkit.getPlayer(owner.getKey());
            if(player==null) { blocks.remove(owner.getKey()); continue; }
            Set<Object> expired=new HashSet<>();
            owner.getValue().entrySet().removeIf(e -> { if(e.getValue()>now) return false; expired.add(e.getKey()); return true; });
            if(!expired.isEmpty()) scaffold.remove(player,expired);
            if(owner.getValue().isEmpty()) blocks.remove(owner.getKey());
        }
    }
    private boolean laidOut(Player player) {
        PlayerInventory inventory=player.getInventory();
        for(int slot=0;slot<inventory.getSize();slot++) {
            ItemStack item=inventory.getItem(slot);
            boolean empty=item==null || item.getType().isAir();
            if(slot==BLOCK_SLOT ? !scaffold.is(item) : slot==Visibility.SLOT ? !visibility.is(item) : !empty) return false;
        }
        return true;
    }
    private void layOut(Player player) {
        PlayerInventory inventory=player.getInventory();
        inventory.clear();
        ItemStack block=scaffold.create();
        block.editMeta(meta -> { meta.displayName(Msg.text(player,"lobby.block.name")); meta.lore(Msg.lines(player,"lobby.block.lore")); });
        inventory.setItem(BLOCK_SLOT,block);
        inventory.setItem(Visibility.SLOT,visibility.item(player));
    }

    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void place(BlockPlaceEvent event) {
        if(!managed(event.getPlayer()) || !scaffold.is(event.getItemInHand())) return;
        blocks.computeIfAbsent(event.getPlayer().getUniqueId(),k -> new HashMap<>())
                .put(scaffold.key(event.getBlockPlaced()),System.currentTimeMillis()+blockMillis);
    }
    @EventHandler(priority=EventPriority.HIGH)
    public void drop(PlayerDropItemEvent event) { if(managed(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.LOW)
    public void click(InventoryClickEvent event) {
        if(!(event.getWhoClicked() instanceof Player player) || !managed(player)) return;
        if(event.getClickedInventory() instanceof PlayerInventory || event.isShiftClick()
                || event.getClick()==ClickType.NUMBER_KEY || event.getClick()==ClickType.SWAP_OFFHAND) event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.LOW)
    public void drag(InventoryDragEvent event) { if(event.getWhoClicked() instanceof Player p && managed(p)) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.LOW)
    public void swap(PlayerSwapHandItemsEvent event) { if(managed(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.LOW)
    public void pickup(EntityPickupItemEvent event) { if(event.getEntity() instanceof Player p && managed(p)) event.setCancelled(true); }
    @EventHandler public void quit(PlayerQuitEvent event) { blocks.remove(event.getPlayer().getUniqueId()); }
}
