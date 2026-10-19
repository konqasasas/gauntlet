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
 * since the save may fire again, and the mobs spawned in this attempt go.
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
        final RunClock clock=new RunClock();
    }
    private final JavaPlugin plugin;
    private final FreeTriggerStore triggers;
    private final CourseItems items;
    private final CourseMobs mobs;
    private final TriggerActions actions;
    /** Builder with the kit, not in a course or the lobby (game mode is checked here). */
    private final Predicate<Player> eligible;
    private final Function<Player,Location> savePoint;
    private final Map<UUID,Run> runs=new HashMap<>();

    public KitRuns(JavaPlugin plugin,Participants participants,FreeTriggerStore triggers,CourseItems items,
                   Predicate<Player> eligible,Function<Player,Location> savePoint) {
        this.plugin=plugin; this.triggers=triggers; this.items=items; this.eligible=eligible; this.savePoint=savePoint;
        mobs=participants.mobs(); actions=participants.actions();
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
        run.clock.reset();
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
        for(BlockPos cell:Participants.cellsAtFeet(player.getBoundingBox())) {
            FreeTriggerStore.Key key=new FreeTriggerStore.Key(world.getUID(),cell);
            if(run.fired.contains(key)) continue;
            Trigger trigger=triggers.get(world.getUID(),cell);
            if(trigger!=null) fire(player,run,world,key,trigger);
        }
        player.sendActionBar(Msg.text(player,"time.seconds",Msg.arg("value",RunClock.seconds(run.clock.elapsed()))));
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
        if(run!=null && event.getAction()!=Action.PHYSICAL && active(event.getPlayer())) run.clock.action();
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void swing(PlayerAnimationEvent event) {
        Run run=runs.get(event.getPlayer().getUniqueId());
        if(run!=null && active(event.getPlayer())) run.clock.action();
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
