package konqasasas.gauntlet.display;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.inventory.*;
import org.bukkit.potion.PotionEffectType;

/**
 * Invisible players show no armor or held items to others. Every viewer is told
 * the slots are empty at the end of each tick, after the server sent any real
 * equipment change in that tick, so the real items never show. The player
 * stays in TAB and sees their own items normally.
 */
public final class InvisibleGear implements Listener {
    private static final List<EquipmentSlot> SLOTS=List.of(EquipmentSlot.HAND,EquipmentSlot.OFF_HAND,
            EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET);
    private static final Map<EquipmentSlot,ItemStack> EMPTY=empty();
    private final Set<UUID> hidden=new HashSet<>();

    public static boolean invisible(Player player) {
        return player.isInvisible() || player.hasPotionEffect(PotionEffectType.INVISIBILITY);
    }
    @EventHandler public void tickEnd(ServerTickEndEvent event) {
        for(Player player:Bukkit.getOnlinePlayers()) {
            if(invisible(player)) { hidden.add(player.getUniqueId()); send(player,EMPTY); }
            else if(hidden.remove(player.getUniqueId())) send(player,real(player));
        }
        hidden.removeIf(id -> Bukkit.getPlayer(id)==null);
    }
    /** Shows the real equipment again (plugin disabled). */
    public void stop() {
        for(UUID id:hidden) { Player player=Bukkit.getPlayer(id); if(player!=null) send(player,real(player)); }
        hidden.clear();
    }
    private static void send(Player target,Map<EquipmentSlot,ItemStack> items) {
        for(Player viewer:Bukkit.getOnlinePlayers())
            if(viewer!=target && viewer.getWorld()==target.getWorld() && viewer.canSee(target)) viewer.sendEquipmentChange(target,items);
    }
    private static Map<EquipmentSlot,ItemStack> real(Player player) {
        Map<EquipmentSlot,ItemStack> items=new EnumMap<>(EquipmentSlot.class);
        for(EquipmentSlot slot:SLOTS) items.put(slot,player.getEquipment().getItem(slot));
        return items;
    }
    private static Map<EquipmentSlot,ItemStack> empty() {
        Map<EquipmentSlot,ItemStack> items=new EnumMap<>(EquipmentSlot.class);
        for(EquipmentSlot slot:SLOTS) items.put(slot,ItemStack.empty());
        return Collections.unmodifiableMap(items);
    }
}
