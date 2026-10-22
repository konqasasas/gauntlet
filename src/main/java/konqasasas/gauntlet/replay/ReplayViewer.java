package konqasasas.gauntlet.replay;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;
import konqasasas.gauntlet.course.*;
import konqasasas.gauntlet.i18n.Msg;
import konqasasas.gauntlet.play.RunClock;
import konqasasas.gauntlet.ui.Inventories;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * /replay (builders only): watching a recorded run in first person.
 *
 * The viewer rides an invisible seat that is moved every tick to where the runner
 * was (riding is smoothed by the client, teleporting the viewer is not), and is
 * turned to where the runner looked. Blocks changed in the run and the things that
 * moved in it are shown to the viewer only (fake blocks, stand-in entities nobody
 * else sees). Hotbar items, used with right click: one tick back, pause (the
 * middle slot), one tick forward, speed (0.5 / 1 / 2 in turn); slot 1 shows the
 * runner's item, slot 9 stops. The items can
 * be heads (config replays.keys.<key>: the texture value). When it ends (or on stop)
 * the viewer gets their place, items and game mode back and the real blocks are
 * shown again.
 */
public final class ReplayViewer implements Listener,CommandExecutor,TabCompleter {
    private static final String ADMIN="gauntlet.admin",TAG="gauntlet_replay";
    private static final double[] SPEEDS={.5,1,2};
    /** Hotbar slots around the middle (pause): back, pause, forward, then speed; slot 1 the runner's item, slot 9 stop. */
    private static final int BACK=3,PAUSE=4,FORWARD=5,SPEED=6,STOP=8;
    private static final class Viewing {
        final Replay replay; final World world;
        final Location from; final ItemStack[] contents; final GameMode mode; final boolean allowFlight,flying;
        /** Changes per cell, in tick order (for going back). */
        final Map<BlockPos,List<Replay.BlockChange>> cells=new HashMap<>();
        final Map<Integer,List<Replay.BlockChange>> byTick=new HashMap<>();
        final Map<Integer,Entity> standIns=new HashMap<>();
        int frame; int speed=1; boolean paused,ending; double carry; String item="";
        /** Server tick of the last control used: one click on a block also arrives as a click in the air. */
        int lastControl=-1;
        /** What the viewer rides. */
        Entity seat;
        Viewing(Replay replay,World world,Player player) {
            this.replay=replay; this.world=world;
            from=player.getLocation(); contents=player.getInventory().getContents().clone(); mode=player.getGameMode();
            allowFlight=player.getAllowFlight(); flying=player.isFlying();
            for(Replay.BlockChange b:replay.blocks()) {
                cells.computeIfAbsent(new BlockPos(b.x(),b.y(),b.z()),k -> new ArrayList<>()).add(b);
                byTick.computeIfAbsent(b.tick(),k -> new ArrayList<>()).add(b);
            }
        }
    }
    private final JavaPlugin plugin;
    private final ReplayStore store;
    private final CourseStore courses;
    private final Map<UUID,Viewing> viewing=new HashMap<>();
    private final NamespacedKey keyTag;
    /** Not while playing a course. */
    private final java.util.function.Predicate<Player> busy;

    public ReplayViewer(JavaPlugin plugin,ReplayStore store,CourseStore courses,java.util.function.Predicate<Player> busy) {
        this.plugin=plugin; this.store=store; this.courses=courses; this.busy=busy;
        keyTag=new NamespacedKey(plugin,"replay_key");
    }
    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        PluginCommand command=Objects.requireNonNull(plugin.getCommand("replay"));
        command.setExecutor(this); command.setTabCompleter(this);
        for(World w:Bukkit.getWorlds()) for(Entity e:w.getEntities()) if(e.getScoreboardTags().contains(TAG)) e.remove();
    }
    public void stop() { for(UUID id:List.copyOf(viewing.keySet())) { Player p=Bukkit.getPlayer(id); if(p!=null) end(p); } }
    public boolean watching(Player player) { return viewing.containsKey(player.getUniqueId()); }

    // ---- Command ----
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!(sender instanceof Player player)) { Msg.send(sender,"settings.console"); return true; }
        if(!player.hasPermission(ADMIN)) { Msg.send(player,"editor.admin-only"); return true; }
        String sub=args.length==0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch(sub) {
            case "list" -> list(player,args.length>1 ? args[1] : null);
            case "play" -> { if(args.length==2) play(player,args[1]); else Msg.send(player,"editor.replay.usage"); }
            case "pause" -> control(player,PAUSE);
            case "back" -> control(player,BACK);
            case "forward" -> control(player,FORWARD);
            case "speed" -> control(player,SPEED);
            case "stop" -> { if(watching(player)) end(player); else Msg.send(player,"editor.replay.not-watching"); }
            default -> Msg.send(player,"editor.replay.usage");
        }
        return true;
    }
    private void list(Player player,String name) {
        UUID who=null;
        if(name!=null) { OfflinePlayer p=Bukkit.getOfflinePlayerIfCached(name); if(p==null) { Msg.send(player,"editor.rank.unknown",Msg.arg("player",name)); return; } who=p.getUniqueId(); }
        UUID target=who;
        Bukkit.getScheduler().runTaskAsynchronously(plugin,() -> {
            try {
                List<ReplayStore.Entry> all=store.entries(target);
                Bukkit.getScheduler().runTask(plugin,() -> {
                    Msg.send(player,"editor.replay.list",Msg.arg("count",all.size()));
                    SimpleDateFormat date=new SimpleDateFormat("MM-dd HH:mm");
                    for(ReplayStore.Entry e:all.subList(0,Math.min(20,all.size())))
                        player.sendMessage(Msg.text(player,"editor.replay.list-line",Msg.arg("id",e.run().toString().substring(0,8)),
                                Msg.arg("player",String.valueOf(Bukkit.getOfflinePlayer(e.player()).getName())),Msg.arg("course",e.course()),Msg.arg("rev",e.rev()),
                                Msg.arg("time",RunClock.seconds(e.ticks())),Msg.arg("at",date.format(new Date(e.createdAt())))));
                });
            } catch(IOException e) { plugin.getLogger().log(Level.WARNING,"Cannot list replays",e); }
        });
    }
    private void play(Player player,String id) {
        if(busy.test(player)) { Msg.send(player,"editor.replay.in-course"); return; }
        if(watching(player)) end(player);
        Bukkit.getScheduler().runTaskAsynchronously(plugin,() -> {
            Replay replay; String error=null;
            try { replay=store.load(id); } catch(IOException e) { replay=null; error=e.getMessage(); }
            Replay found=replay; String failed=error;
            Bukkit.getScheduler().runTask(plugin,() -> {
                if(!player.isOnline()) return;
                if(found==null) { Msg.send(player,failed==null ? "editor.replay.not-found" : "editor.replay.read-failed",Msg.arg("id",id)); return; }
                Course course=courses.get(found.course());
                World world=course==null ? null : Bukkit.getWorld(course.world());
                if(world==null || found.frames().isEmpty()) { Msg.send(player,"editor.replay.no-world"); return; }
                begin(player,found,world);
            });
        });
    }
    private void begin(Player player,Replay replay,World world) {
        Viewing v=new Viewing(replay,world,player);
        viewing.put(player.getUniqueId(),v);
        player.closeInventory();
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(true); player.setFlying(true);
        ItemStack[] hotbar=new ItemStack[player.getInventory().getSize()];
        hotbar[BACK]=key(player,BACK,"back",Material.RED_DYE);
        hotbar[PAUSE]=key(player,PAUSE,"pause",Material.CLOCK);
        hotbar[FORWARD]=key(player,FORWARD,"forward",Material.LIME_DYE);
        hotbar[SPEED]=key(player,SPEED,"speed",Material.SUGAR);
        hotbar[STOP]=key(player,STOP,"stop",Material.BARRIER);
        Inventories.apply(player.getInventory(),hotbar);
        player.getInventory().setHeldItemSlot(PAUSE);
        // The seat: the viewer rides it, so the client smooths the movement between ticks.
        Replay.Frame first=replay.frames().getFirst();
        Location start=new Location(world,first.x(),first.y(),first.z(),first.yaw(),first.pitch());
        player.teleport(start);
        v.seat=world.spawn(start,ItemDisplay.class,d -> { prepare(d); d.setTeleportDuration(1); });
        player.showEntity(plugin,v.seat);
        v.seat.addPassenger(player);
        Msg.send(player,"editor.replay.started",Msg.arg("course",replay.course()),Msg.arg("time",RunClock.seconds(replay.ticks())));
        show(player,v);
    }
    private void end(Player player) {
        Viewing v=viewing.get(player.getUniqueId()); if(v==null) return;
        v.ending=true;
        if(v.seat!=null) { v.seat.removePassenger(player); v.seat.remove(); }
        viewing.remove(player.getUniqueId());
        for(BlockPos c:v.cells.keySet()) player.sendBlockChange(new Location(v.world,c.x(),c.y(),c.z()),v.world.getBlockAt(c.x(),c.y(),c.z()).getBlockData());
        v.standIns.values().forEach(Entity::remove);
        Inventories.apply(player.getInventory(),v.contents);
        player.setGameMode(v.mode);
        player.setAllowFlight(v.allowFlight); if(v.allowFlight) player.setFlying(v.flying);
        player.teleport(v.from);
        Msg.send(player,"editor.replay.ended");
    }

    // ---- Playing ----
    @EventHandler public void tick(ServerTickEndEvent event) {
        for(UUID id:List.copyOf(viewing.keySet())) {
            Player player=Bukkit.getPlayer(id); Viewing v=viewing.get(id);
            if(player==null) { viewing.remove(id); continue; }
            int last=v.replay.frames().size()-1;
            if(!v.paused) {
                v.carry+=SPEEDS[v.speed];
                while(v.carry>=1 && v.frame<last) { v.carry-=1; v.frame++; forwardBlocks(player,v,v.frame); }
                if(v.frame>=last) { end(player); continue; }
            }
            show(player,v);
        }
    }
    /** Puts the viewer where the runner was at the current frame, with the things around. */
    private void show(Player player,Viewing v) {
        Replay.Frame f=v.replay.frames().get(v.frame);
        // Sneaking lowers the eyes; the viewer cannot sneak for them, so the camera goes down instead.
        Location at=new Location(v.world,f.x(),f.y()-(f.sneaking() ? .35 : 0),f.z(),f.yaw(),f.pitch());
        if(v.seat!=null && v.seat.isValid()) {
            v.seat.teleport(at); // Passengers stay on (Paper keeps them).
            if(!v.seat.getPassengers().contains(player)) v.seat.addPassenger(player);
        } else player.teleport(at);
        player.setRotation(f.yaw(),f.pitch());
        if(f.swing() && !v.paused) player.swingMainHand();
        if(!f.item().equals(v.item)) {
            v.item=f.item();
            Material m=f.item().isEmpty() ? null : Material.matchMaterial(f.item());
            Inventories.put(player.getInventory(),0,m==null || !m.isItem() ? null : new ItemStack(m));
        }
        things(player,v,v.replay.things().size()>v.frame ? v.replay.things().get(v.frame) : List.of());
        player.sendActionBar(Msg.text(player,v.paused ? "editor.replay.bar-paused" : "editor.replay.bar",
                Msg.arg("time",RunClock.seconds(v.frame)),Msg.arg("total",RunClock.seconds(v.replay.ticks())),Msg.arg("speed",SPEEDS[v.speed]),Msg.arg("frame",v.frame)));
    }
    private void forwardBlocks(Player player,Viewing v,int tick) {
        for(Replay.BlockChange b:v.byTick.getOrDefault(tick,List.of())) send(player,v,b.x(),b.y(),b.z(),b.data());
    }
    /** Every changed cell as it was at the tick (going back): the last change up to it, else the real block. */
    private void blocksAt(Player player,Viewing v,int tick) {
        for(var e:v.cells.entrySet()) {
            Replay.BlockChange at=null;
            for(Replay.BlockChange b:e.getValue()) if(b.tick()<=tick) at=b;
            BlockPos c=e.getKey();
            if(at!=null) send(player,v,c.x(),c.y(),c.z(),at.data());
            else player.sendBlockChange(new Location(v.world,c.x(),c.y(),c.z()),v.world.getBlockAt(c.x(),c.y(),c.z()).getBlockData());
        }
    }
    private static void send(Player player,Viewing v,int x,int y,int z,String data) {
        try { player.sendBlockChange(new Location(v.world,x,y,z),Bukkit.createBlockData(data)); }
        catch(IllegalArgumentException ignored) {}
    }
    /** Stand-ins for the recorded things, seen by the viewer only. */
    private void things(Player player,Viewing v,List<Replay.Thing> now) {
        Set<Integer> present=new HashSet<>();
        for(Replay.Thing t:now) {
            present.add(t.id());
            Entity e=v.standIns.get(t.id());
            boolean block=!t.data().isEmpty();
            Location at=new Location(v.world,t.x()-(block ? .5 : 0),t.y(),t.z()-(block ? .5 : 0),t.yaw(),0);
            if(e==null || !e.isValid()) { e=spawn(player,t,at); if(e==null) continue; v.standIns.put(t.id(),e); }
            else e.teleport(at);
        }
        v.standIns.entrySet().removeIf(entry -> { if(present.contains(entry.getKey())) return false; entry.getValue().remove(); return true; });
    }
    private Entity spawn(Player viewer,Replay.Thing t,Location at) {
        try {
            Entity e;
            if(!t.data().isEmpty()) {
                BlockData data=Bukkit.createBlockData(t.data());
                e=at.getWorld().spawn(at,BlockDisplay.class,d -> { prepare(d); d.setBlock(data); });
            } else {
                EntityType type=Registry.ENTITY_TYPE.get(NamespacedKey.fromString(t.type()));
                if(type==null || type.getEntityClass()==null || !type.isSpawnable()) return null;
                e=at.getWorld().spawn(at,type.getEntityClass(),x -> {
                    prepare(x);
                    if(x instanceof LivingEntity l) { l.setAI(false); l.setInvulnerable(true); l.setSilent(true); l.setCollidable(false); }
                    x.setGravity(false);
                });
            }
            viewer.showEntity(plugin,e);
            return e;
        } catch(RuntimeException ex) { return null; }
    }
    private static void prepare(Entity e) { e.setPersistent(false); e.addScoreboardTag(TAG); e.setVisibleByDefault(false); }

    // ---- Keys ----
    private void control(Player player,int key) {
        Viewing v=viewing.get(player.getUniqueId());
        if(v==null) { Msg.send(player,"editor.replay.not-watching"); return; }
        switch(key) {
            case PAUSE -> v.paused=!v.paused;
            case BACK -> { v.paused=true; if(v.frame>0) { v.frame--; blocksAt(player,v,v.frame); } }
            case FORWARD -> { v.paused=true; if(v.frame<v.replay.frames().size()-1) { v.frame++; forwardBlocks(player,v,v.frame); } }
            case SPEED -> v.speed=(v.speed+1)%SPEEDS.length;
            case STOP -> { end(player); return; }
            default -> {}
        }
        show(player,v);
    }
    /** A control item: a head when replays.keys.<name> gives a texture, else the plain item. */
    private ItemStack key(Player player,int slot,String name,Material plain) {
        String texture=plugin.getConfig().getString("replays.keys."+name,"").strip();
        ItemStack item;
        if(texture.isEmpty()) item=Msg.icon(player,plain,"editor.replay.key."+name);
        else {
            ItemStack head=Msg.icon(player,Material.PLAYER_HEAD,"editor.replay.key."+name);
            head.editMeta(org.bukkit.inventory.meta.SkullMeta.class,meta -> {
                var profile=Bukkit.createProfile(UUID.nameUUIDFromBytes(texture.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                profile.setProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures",texture));
                meta.setPlayerProfile(profile);
            });
            item=head;
        }
        item.editMeta(meta -> meta.getPersistentDataContainer().set(keyTag,org.bukkit.persistence.PersistentDataType.INTEGER,slot));
        return item;
    }
    /** Right click with a control item. */
    @EventHandler(priority=EventPriority.LOWEST)
    public void interact(PlayerInteractEvent event) {
        if(!watching(event.getPlayer())) return;
        event.setCancelled(true);
        if(event.getHand()!=EquipmentSlot.HAND || (event.getAction()!=Action.RIGHT_CLICK_AIR && event.getAction()!=Action.RIGHT_CLICK_BLOCK)) return;
        Player player=event.getPlayer(); Viewing v=viewing.get(player.getUniqueId());
        // Clicking a block: the cancelled click makes the server send the real blocks there, over the
        // replay's blocks; show the replay's blocks again just after.
        if(event.getAction()==Action.RIGHT_CLICK_BLOCK) Bukkit.getScheduler().runTask(plugin,() -> { if(viewing.get(player.getUniqueId())==v) blocksAt(player,v,v.frame); });
        // A click on a block is followed by a click in the air from the same press: use it once.
        int now=Bukkit.getCurrentTick();
        if(v.lastControl==now) return;
        ItemStack item=event.getItem();
        Integer key=item==null || !item.hasItemMeta() ? null : item.getItemMeta().getPersistentDataContainer().get(keyTag,org.bukkit.persistence.PersistentDataType.INTEGER);
        if(key!=null) { v.lastControl=now; control(player,key); }
    }
    /** Sneaking would get off the seat. */
    @EventHandler(priority=EventPriority.LOWEST)
    public void dismount(org.bukkit.event.entity.EntityDismountEvent event) {
        if(event.getEntity() instanceof Player p && viewing.containsKey(p.getUniqueId()) && !viewing.get(p.getUniqueId()).ending) event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.LOWEST)
    public void interactEntity(PlayerInteractEntityEvent event) { if(watching(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.LOWEST)
    public void place(BlockPlaceEvent event) { if(watching(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.LOWEST)
    public void drop(PlayerDropItemEvent event) { if(watching(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.LOWEST)
    public void swap(PlayerSwapHandItemsEvent event) { if(watching(event.getPlayer())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.LOWEST)
    public void click(InventoryClickEvent event) { if(event.getWhoClicked() instanceof Player p && watching(p)) event.setCancelled(true); }
    @EventHandler public void quit(PlayerQuitEvent event) { if(watching(event.getPlayer())) end(event.getPlayer()); }

    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        if(args.length==1) return List.of("list","play","pause","back","forward","speed","stop").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if(args.length==2 && args[0].equalsIgnoreCase("list")) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        return List.of();
    }
}
