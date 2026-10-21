package konqasasas.gauntlet.play;

import io.papermc.paper.event.packet.ClientTickEndEvent;
import java.util.*;
import java.util.function.*;
import konqasasas.gauntlet.course.*;
import konqasasas.gauntlet.i18n.Msg;
import konqasasas.gauntlet.ui.Inventories;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Trying out a section with the builder kit, outside any course: free triggers
 * fire once per attempt, and the action bar shows the time since leaving the
 * save point. An attempt starts at a save (F) or a return to it (Q).
 *
 * On a return, hotbar slots 1-3 go back to how they were at the save (only
 * slots that hold the kit block or a trigger item, then or now), triggers fired
 * since the save may fire again, the mobs spawned in this attempt go, and
 * trigger water goes back to how it was at the save (with the time it had left).
 * Shift+Q also re-arms every trigger, including those fired before the save.
 * Shift+left click (or /gauntlet placeable) turns the placing limit on or off:
 * while on, placing a block anywhere but the placeable cells outside the courses
 * is refused and goes back to the save point, as a course would. The kit block
 * glints while the limit is on (the action bar keeps showing only the time).
 * Only builders in Adventure with a save and the kit take part.
 */
public final class KitRuns implements Listener {
    private static final double MOVE_EPSILON_SQUARED=1e-8;
    private static final class Run {
        ItemStack[] savedSlots;
        Set<FreeTriggerStore.Key> fired=new HashSet<>(),savedFired=new HashSet<>();
        /** Mobs out per action key; attempt = keys whose mobs were spawned in this attempt. */
        final Map<String,List<UUID>> mobs=new HashMap<>();
        final Set<String> attempt=new HashSet<>();
        /** Trigger water at the save, put back on a return. */
        List<WaterFlows.Saved> savedWater=List.of();
        final RunClock clock=new RunClock();
        /** Placing only on the placeable cells outside the courses. */
        boolean limit;
    }
    private final JavaPlugin plugin;
    private final FreeTriggerStore triggers;
    private final CourseItems items;
    private final CourseMobs mobs;
    private final TriggerActions actions;
    private final WaterFlows water;
    /** Builder with the kit, not in a course or the lobby (game mode is checked here). */
    private final Predicate<Player> eligible;
    private final Function<Player,Location> savePoint;
    private final FreePlaceableStore placeable;
    /** The kit block itself (not a trigger item). */
    private final Predicate<ItemStack> kitBlock;
    /** Back to the save point, as Q does. */
    private final Consumer<Player> back;
    private final Map<UUID,Run> runs=new HashMap<>();

    public KitRuns(JavaPlugin plugin,Participants participants,FreeTriggerStore triggers,CourseItems items,
                   Predicate<Player> eligible,Function<Player,Location> savePoint,
                   FreePlaceableStore placeable,Predicate<ItemStack> kitBlock,Consumer<Player> back) {
        this.plugin=plugin; this.triggers=triggers; this.items=items; this.eligible=eligible; this.savePoint=savePoint;
        this.placeable=placeable; this.kitBlock=kitBlock; this.back=back;
        mobs=participants.mobs(); actions=participants.actions(); water=participants.water();
    }
    public void start() { plugin.getServer().getPluginManager().registerEvents(this,plugin); }
    public void stop() {
        for(Run run:runs.values()) run.mobs.values().forEach(mobs::remove);
        runs.clear();
    }
    private Run run(Player player) { return runs.computeIfAbsent(player.getUniqueId(),k -> new Run()); }
    private boolean active(Player player) {
        return player.getGameMode()==GameMode.ADVENTURE && !player.isDead() && eligible.test(player) && savePoint.apply(player)!=null;
    }
    /** A kit block or an item handed out by a trigger. */
    private boolean kitItem(ItemStack item) { return items.ref(item)!=null; }

    /** F: a new attempt from here. Mobs spawned so far stay; triggers fired so far stay fired. */
    public void saved(Player player) {
        Run run=run(player);
        PlayerInventory inventory=player.getInventory();
        run.savedSlots=new ItemStack[Course.COURSE_SLOTS];
        for(int slot=0;slot<Course.COURSE_SLOTS;slot++) {
            ItemStack item=inventory.getItem(slot);
            run.savedSlots[slot]=item==null || item.getType().isAir() ? null : item.clone();
        }
        run.savedFired=new HashSet<>(run.fired);
        run.savedWater=water.snapshot(player);
        run.attempt.clear();
        run.clock.reset();
    }
    /** Q (or lava): back at the save point. */
    public void returned(Player player) {
        Run run=run(player);
        for(String key:run.attempt) { List<UUID> ids=run.mobs.remove(key); if(ids!=null) mobs.remove(ids); }
        run.attempt.clear();
        if(run.savedSlots!=null) {
            PlayerInventory inventory=player.getInventory();
            for(int slot=0;slot<Course.COURSE_SLOTS;slot++) {
                ItemStack saved=run.savedSlots[slot];
                if(kitItem(saved) || kitItem(inventory.getItem(slot))) Inventories.put(inventory,slot,saved==null ? null : saved.clone());
            }
        }
        run.fired=new HashSet<>(run.savedFired);
        water.restore(player,run.savedWater);
        run.clock.reset();
    }

    /** Shift+left click or /gauntlet placeable: the placing limit on or off. */
    public void toggleLimit(Player player) {
        Run run=run(player);
        run.limit=!run.limit;
        showLimit(player,run);
        Msg.send(player,run.limit ? "editor.kit.limit-on" : "editor.kit.limit-off",Msg.arg("count",placeable.cells(player.getWorld().getUID()).size()));
    }
    /** The kit blocks glint while the limit is on. */
    private void showLimit(Player player,Run run) {
        PlayerInventory inventory=player.getInventory();
        for(int slot=0;slot<inventory.getSize();slot++) {
            ItemStack item=inventory.getItem(slot);
            if(!kitBlock.test(item)) continue;
            Boolean glint=item.getItemMeta().hasEnchantmentGlintOverride() ? item.getItemMeta().getEnchantmentGlintOverride() : null;
            if(Boolean.valueOf(run.limit).equals(glint) || (!run.limit && glint==null)) continue;
            ItemStack copy=item.clone();
            copy.editMeta(meta -> meta.setEnchantmentGlintOverride(run.limit ? Boolean.TRUE : null));
            Inventories.put(inventory,slot,copy);
        }
    }
    /** Shift+left click with the kit block, in Adventure (in Creative it breaks blocks). */
    private boolean limitGesture(Player player) {
        return player.isSneaking() && player.getGameMode()==GameMode.ADVENTURE && kitBlock.test(player.getInventory().getItemInMainHand()) && eligible.test(player);
    }
    @EventHandler(priority=EventPriority.LOW)
    public void limitClick(PlayerInteractEvent event) {
        if(event.getHand()!=EquipmentSlot.HAND || (event.getAction()!=Action.LEFT_CLICK_AIR && event.getAction()!=Action.LEFT_CLICK_BLOCK)) return;
        if(!limitGesture(event.getPlayer())) return;
        event.setCancelled(true);
        toggleLimit(event.getPlayer());
    }
    /** With the limit on, a block anywhere but a placeable cell is refused and the attempt goes back to the save point. */
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void placeOutside(BlockPlaceEvent event) {
        Player player=event.getPlayer(); Run run=runs.get(player.getUniqueId());
        if(run==null || !run.limit || !active(player)) return;
        var block=event.getBlockPlaced();
        if(placeable.contains(block.getWorld().getUID(),new BlockPos(block.getX(),block.getY(),block.getZ()))) return;
        event.setCancelled(true);
        konqasasas.gauntlet.ui.Sounds.system(player,Sound.ENTITY_VILLAGER_NO,.7f,1f);
        // After the event: moving the player inside it can be undone by the rest of that placement.
        Bukkit.getScheduler().runTask(plugin,() -> { if(player.isOnline()) back.accept(player); });
    }

    /** Shift+Q: every trigger may fire again, even those fired before the save. */
    public void rearm(Player player) {
        Run run=run(player);
        run.fired.clear(); run.savedFired.clear();
    }

    @EventHandler public void clientTick(ClientTickEndEvent event) {
        Player player=event.getPlayer();
        if(!active(player)) return;
        Run run=run(player);
        Location at=player.getLocation(),save=savePoint.apply(player);
        boolean moved=run.clock.phase()==RunClock.Phase.WAITING
                && (save.getWorld()!=at.getWorld() || at.toVector().distanceSquared(save.toVector())>MOVE_EPSILON_SQUARED);
        run.clock.tick(moved);
        World world=at.getWorld();
        for(BlockPos cell:Participants.platesPressed(world,player.getBoundingBox())) {
            FreeTriggerStore.Key key=new FreeTriggerStore.Key(world.getUID(),cell);
            if(run.fired.contains(key)) continue;
            Trigger trigger=triggers.get(world.getUID(),cell);
            if(trigger!=null) fire(player,run,world,key,trigger);
        }
        player.sendActionBar(Msg.text(player,"time.seconds",Msg.arg("value",RunClock.seconds(run.clock.elapsed()))));
        // A return or a refill may hand back a kit block without the glint.
        if(Bukkit.getCurrentTick()%10==0) showLimit(player,run);
    }
    private void fire(Player player,Run run,World world,FreeTriggerStore.Key key,Trigger trigger) {
        run.fired.add(key);
        for(int i=0;i<trigger.actions().size();i++) {
            TriggerAction action=trigger.actions().get(i);
            String actionKey=world.getUID()+":"+trigger.pos().describe()+"#"+i;
            List<UUID> before=run.mobs.get(actionKey);
            try { actions.run(player,world,actionKey,action,run.mobs,(slot,item) -> item); }
            catch(RuntimeException e) { plugin.getLogger().log(java.util.logging.Level.SEVERE,"Free trigger action failed at "+trigger.pos().describe(),e); }
            if(run.mobs.get(actionKey)!=before) run.attempt.add(actionKey);
        }
    }

    // ---- Clock input: moving or using an item starts the time ----
    @EventHandler(priority=EventPriority.MONITOR)
    public void use(PlayerInteractEvent event) {
        Run run=runs.get(event.getPlayer().getUniqueId());
        // Turning the limit on or off is not a move of the attempt.
        if(run!=null && event.getAction()!=Action.PHYSICAL && active(event.getPlayer()) && !limitGesture(event.getPlayer())) run.clock.action();
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void swing(PlayerAnimationEvent event) {
        Run run=runs.get(event.getPlayer().getUniqueId());
        if(run!=null && active(event.getPlayer()) && !limitGesture(event.getPlayer())) run.clock.action();
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void place(BlockPlaceEvent event) {
        Run run=runs.get(event.getPlayer().getUniqueId());
        if(run!=null && active(event.getPlayer())) run.clock.action();
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        Run run=runs.remove(event.getPlayer().getUniqueId());
        if(run!=null) run.mobs.values().forEach(mobs::remove);
    }
}
