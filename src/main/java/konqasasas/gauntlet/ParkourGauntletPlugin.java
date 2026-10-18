package konqasasas.gauntlet;

import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import konqasasas.gauntlet.chat.*;
import konqasasas.gauntlet.course.*;
import konqasasas.gauntlet.data.*;
import konqasasas.gauntlet.display.*;
import konqasasas.gauntlet.i18n.*;
import konqasasas.gauntlet.lobby.Lobby;
import konqasasas.gauntlet.pack.ResourcePacks;
import konqasasas.gauntlet.play.*;
import konqasasas.gauntlet.ui.*;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.*;
import org.bukkit.util.Vector;

public final class ParkourGauntletPlugin extends JavaPlugin implements Listener,CommandExecutor,TabCompleter {
    private StateStore state;
    private ToolItems tools;
    private Database database;
    private PlayerData playerData;
    private CourseStore courseStore;
    private RunLog runs;
    private CourseEditor courses;
    private Participants participants;
    private ChatTranslation chat;
    private NameTags nameTags;
    private Visibility visibility;
    private Lobby lobby;
    private TabList tabList;
    private Sidebar sidebar;
    private PlaceableDisplays placeable;
    private InvisibleGear invisibleGear;
    private DialogMenu menu;
    private PlayerDisplay display;
    private ResourcePacks packs;
    private final Map<UUID,Integer> lastUse=new HashMap<>();
    private final Set<UUID> pendingRefills=new HashSet<>();
    private final LavaExtinguishTracker lavaExtinguish=new LavaExtinguishTracker();
    private final Set<UUID> pendingLavaRetries=new HashSet<>();
    private final Map<UUID,Integer> lavaRetryFailures=new HashMap<>();

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            var data=getDataFolder().toPath();
            Messages messages=Messages.load(getClassLoader(),data.resolve("lang"),getLogger());
            database=Database.open(data.resolve("gauntlet.db"),getLogger());
            LegacyImport.run(database,data,getLogger());
            runs=new RunLog(database); runs.load();
            playerData=new PlayerData(this,database);
            Msg.init(messages,playerData::uiLanguage);
            Sounds.init(playerData);
            playerData.start();

            state=new StateStore(data.resolve("state.json"));
            tools=new ToolItems(new NamespacedKey(this,"tool"));
            cleanup(state.allBlocks());
            getServer().getPluginManager().registerEvents(this,this);
            Objects.requireNonNull(getCommand("gauntlet")).setExecutor(this);
            getCommand("gauntlet").setTabCompleter(this);
            courseStore=new CourseStore(data.resolve("courses"));
            TemplateStore templateStore=new TemplateStore(data.resolve("templates.json"));
            Scaffold scaffold=new Scaffold() {
                @Override public ItemStack create() { return tools.create(ToolItems.Kind.SCAFFOLD); }
                @Override public boolean is(ItemStack item) { return tools.kind(item)==ToolItems.Kind.SCAFFOLD; }
                @Override public void clear(Player player) { clearExcept(player,Set.of()); }
                @Override public Set<Object> placed(Player player) {
                    return state.blocks(player.getUniqueId()).stream().map(b -> (Object)b.block()).collect(java.util.stream.Collectors.toUnmodifiableSet());
                }
                @Override public void clearExcept(Player player,Set<Object> keep) {
                    try { cleanup(state.blocks(player.getUniqueId()).stream().filter(b -> !keep.contains(b.block())).toList()); }
                    catch(IOException|RuntimeException e) { getLogger().log(Level.SEVERE,"Scaffold cleanup incomplete; journal retained",e); }
                }
                @Override public void remove(Player player,Set<Object> blocks) {
                    try { cleanup(state.blocks(player.getUniqueId()).stream().filter(b -> blocks.contains(b.block())).toList()); }
                    catch(IOException|RuntimeException e) { getLogger().log(Level.SEVERE,"Scaffold cleanup incomplete; journal retained",e); }
                }
                @Override public Object key(Block block) { return ParkourGauntletPlugin.key(block); }
            };
            CourseItems courseItems=new CourseItems(templateStore,scaffold,new NamespacedKey(this,"course_item"));
            participants=new Participants(this,courseStore,runs,playerData,new ReturnStore(data.resolve("participants.json")),scaffold,courseItems);

            DisplayStyle style;
            try { style=DisplayStyle.load(getClassLoader(),data.resolve("display.yml"),getLogger()); }
            catch(IOException e) {
                // A broken display.yml must not take the whole plugin down: start with the bundled look.
                getLogger().log(Level.SEVERE,"Ignoring display.yml, using the bundled display settings",e);
                style=DisplayStyle.load(getClassLoader(),null,getLogger());
            }
            display=new PlayerDisplay(new Ranks(),style);
            nameTags=new NameTags(this,display,playerData,participants::active);
            Teleports.beforeTeleport(nameTags::detach);
            visibility=new Visibility(this,playerData,nameTags);
            participants.visibility(visibility);
            lobby=new Lobby(this,scaffold,visibility,participants::active);
            tabList=new TabList(this,display);
            sidebar=new Sidebar(this,display,playerData,this::courseView);

            participants.start();
            courses=new CourseEditor(this,courseStore,templateStore,courseItems,new NamespacedKey(this,"tool"),participants);
            courses.start();
            // Players in a course see its cells; builders see the course they hold the wand or marker for.
            placeable=new PlaceableDisplays(this,courseStore,p -> {
                Participants.View view=participants.view(p);
                return view!=null ? view.course() : courses.shownCourse(p);
            });
            placeable.start();
            nameTags.start();
            visibility.start();
            lobby.start();
            tabList.start();
            sidebar.start();
            getServer().getPluginManager().registerEvents(new ChatFormat(display),this);
            getServer().getPluginManager().registerEvents(new ServerListMotd(display,getConfig().getString("server.name","Catena Online")),this);
            getServer().getPluginManager().registerEvents(new PlayerContact(),this);
            invisibleGear=new InvisibleGear();
            getServer().getPluginManager().registerEvents(invisibleGear,this);
            chat=new ChatTranslation(this,playerData);
            chat.start();
            new SettingsCommands(this,playerData,visibility).start();
            menu=new DialogMenu(this,playerData,lobby,participants);
            menu.start();
            packs=new ResourcePacks(this);
            packs.start();
            menu.packState(packs::menuState);
            getLogger().info("Parkour Gauntlet ready: /gauntlet kit, /course (Paper 26.3)");
        } catch(IOException|RuntimeException e) {
            getLogger().log(Level.SEVERE,"Cannot initialize Parkour Gauntlet; existing state was preserved",e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    @Override public void onDisable() {
        if(packs!=null) packs.stop();
        if(placeable!=null) placeable.stop();
        if(invisibleGear!=null) invisibleGear.stop();
        if(courses!=null) courses.stop();
        if(chat!=null) chat.stop();
        if(lobby!=null) lobby.stop();
        if(sidebar!=null) sidebar.stop();
        if(tabList!=null) tabList.stop();
        if(nameTags!=null) nameTags.stop();
        if(visibility!=null) visibility.stop();
        if(participants!=null) participants.stop();
        if(state!=null) try { cleanup(state.allBlocks()); }
        catch(IOException|RuntimeException e) { getLogger().log(Level.SEVERE,"Cleanup incomplete; journal retained for next start",e); }
        if(playerData!=null) playerData.stop();
        if(database!=null) database.close();
        lastUse.clear(); pendingRefills.clear(); lavaExtinguish.clear();
        pendingLavaRetries.clear(); lavaRetryFailures.clear();
    }
    /** The course part of the sidebar, or null outside a course. */
    private Sidebar.CourseView courseView(Player player) {
        Participants.View view=participants.view(player); if(view==null) return null;
        Course course=courseStore.get(view.course()); if(course==null) return null;
        RunLog.Stats stats=runs.stats(course.id(),course.rev(),player.getUniqueId());
        String best=stats.bestClearTicks()!=null ? "Clear ✓ "+RunClock.seconds(stats.bestClearTicks())+"s"
                : stats.bestProgress()!=null && new RouteProgress(course.route()).usable() ? RouteProgress.percent(stats.bestProgress(),false) : "—";
        return new Sidebar.CourseView(course.name(),best,stats.attempts());
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(args.length==2 && args[0].equalsIgnoreCase("reload") && args[1].equalsIgnoreCase("lang")) {
            try { Msg.reload(Messages.load(getClassLoader(),getDataFolder().toPath().resolve("lang"),getLogger())); Msg.send(sender,"editor.lang.reloaded"); }
            catch(IOException e) { Msg.send(sender,"editor.lang.reload-failed",Msg.arg("error",String.valueOf(e.getMessage()))); getLogger().log(Level.SEVERE,"Cannot reload language files",e); }
            return true;
        }
        if(args.length==2 && args[0].equalsIgnoreCase("reload") && args[1].equalsIgnoreCase("display")) {
            if(display==null) return true;
            try { display.style(DisplayStyle.load(getClassLoader(),getDataFolder().toPath().resolve("display.yml"),getLogger())); Msg.send(sender,"editor.display.reloaded"); }
            catch(IOException e) { Msg.send(sender,"editor.display.reload-failed",Msg.arg("error",String.valueOf(e.getMessage()))); getLogger().log(Level.SEVERE,"Cannot reload display.yml",e); }
            return true;
        }
        if(args.length==2 && args[0].equalsIgnoreCase("reload") && args[1].equalsIgnoreCase("packs")) {
            Msg.send(sender,packs!=null && packs.reload() ? "editor.packs.reloaded" : "editor.packs.disabled");
            return true;
        }
        if(args.length==2 && args[0].equalsIgnoreCase("reload") && args[1].equalsIgnoreCase("menu")) {
            Msg.send(sender,menu!=null && menu.reload() ? "editor.menu.reloaded" : "editor.menu.reload-failed");
            return true;
        }
        if(!(sender instanceof Player player)) { Msg.send(sender,"editor.kit.console"); return true; }
        if(!allowed(player)) return true;
        if(args.length!=1 || !args[0].equalsIgnoreCase("kit")) { tell(player,"editor.kit.usage"); return true; }
        if(!tools.grant(player)) tell(player,"editor.kit.full");
        else tell(player,"editor.kit.given");
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        if(args.length==1) return List.of("kit","reload").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if(args.length==2 && args[0].equalsIgnoreCase("reload")) return List.of("lang","menu","display","packs");
        return List.of();
    }
    private boolean allowed(Player player) {
        if(player.hasPermission("gauntlet.admin")) return true;
        tell(player,"editor.kit.admin-only"); return false;
    }
    private void tell(Player player,String key,TagResolver... args) { Msg.send(player,key,args); }
    static BlockKey key(Block block) { return new BlockKey(block.getWorld().getUID(),block.getX(),block.getY(),block.getZ()); }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void drop(PlayerDropItemEvent event) {
        ToolItems.Kind kind=tools.kind(event.getItemDrop().getItemStack()); if(kind==null) return;
        event.setCancelled(true);
        Player player=event.getPlayer(); if(!allowed(player)) return;
        if(kind==ToolItems.Kind.CHECKPOINT) saveCheckpoint(player);
        else if(kind==ToolItems.Kind.SCAFFOLD) refillSoon(player);
    }
    private void saveCheckpoint(Player player) {
        Location location=player.getLocation();
        if(!player.isOnGround() || !hasPermanentSupport(location,player.getBoundingBox())) { tell(player,"editor.cp.not-ground"); return; }
        try {
            state.checkpoint(player.getUniqueId(),new Checkpoint(location.getWorld().getUID(),location.getX(),location.getY(),location.getZ(),location.getYaw(),location.getPitch()));
            tell(player,"editor.cp.set");
            Sounds.system(player,Sound.ENTITY_EXPERIENCE_ORB_PICKUP,1f,1f);
            getLogger().info("CP_SET player="+player.getUniqueId()+" world="+location.getWorld().getName()+" position="+location.getX()+","+location.getY()+","+location.getZ()+" yaw="+location.getYaw()+" pitch="+location.getPitch());
        } catch(IOException e) { failure(player,"editor.cp.save-failed",e); }
    }
    private boolean hasPermanentSupport(Location location,BoundingBox feet) {
        List<Block> support=GroundGeometry.supporting(location.getWorld(),feet,location.getY());
        return !support.isEmpty() && support.stream().noneMatch(block -> state.contains(key(block)));
    }

    // Air clicks on these non-usable vanilla items are predictively cancelled;
    // receive them anyway so the custom right-click action also works in air.
    @EventHandler(priority=EventPriority.HIGHEST)
    public void interact(PlayerInteractEvent event) {
        if(event.getAction()!=Action.RIGHT_CLICK_AIR && event.getAction()!=Action.RIGHT_CLICK_BLOCK) return;
        Player player=event.getPlayer();
        ToolItems.Kind main=tools.kind(player.getInventory().getItemInMainHand());
        if(event.getHand()==EquipmentSlot.OFF_HAND && (main==ToolItems.Kind.CHECKPOINT || main==ToolItems.Kind.MODE)) {
            event.setCancelled(true); return;
        }
        ToolItems.Kind kind=tools.kind(event.getItem());
        if(kind!=ToolItems.Kind.CHECKPOINT && kind!=ToolItems.Kind.MODE) return;
        event.setCancelled(true); if(!allowed(player)) return;
        int tick=Bukkit.getCurrentTick();
        if(Objects.equals(lastUse.put(player.getUniqueId(),tick),tick)) return;
        if(kind==ToolItems.Kind.CHECKPOINT) retry(player);
        else {
            GameMode mode=player.getGameMode()==GameMode.CREATIVE ? GameMode.ADVENTURE : GameMode.CREATIVE;
            player.setGameMode(mode); tell(player,"editor.mode.changed",Msg.arg("mode",mode.name()));
            Sounds.system(player,Sound.BLOCK_NOTE_BLOCK_PLING,1f,1f);
        }
    }
    private boolean retry(Player player) {
        Checkpoint cp=state.checkpoint(player.getUniqueId());
        if(cp==null) { tell(player,"editor.cp.none"); return false; }
        World world=Bukkit.getWorld(cp.world());
        if(world==null) { tell(player,"editor.cp.world-unloaded"); return false; }
        Location target=new Location(world,cp.x(),cp.y(),cp.z(),cp.yaw(),cp.pitch());
        int chunkX=target.getBlockX()>>4,chunkZ=target.getBlockZ()>>4;
        if(!world.isChunkLoaded(chunkX,chunkZ) && !world.loadChunk(chunkX,chunkZ,false)) { tell(player,"editor.cp.chunk"); return false; }
        // Never clear the course trial if another plugin rejects this teleport.
        if(!Teleports.to(player,target)) { tell(player,"editor.cp.denied"); return false; }
        stopMotion(player);
        try {
            cleanup(state.blocks(player.getUniqueId()));
            tools.refill(player); player.updateInventory();
        } catch(IOException|RuntimeException e) { failure(player,"editor.cp.cleanup-failed",e); }
        return true;
    }
    private void stopMotion(Player player) {
        if(player.isFlying()) player.setFlying(false);
        player.setSprinting(false); player.setVelocity(new Vector()); player.setFallDistance(0);
    }

    @EventHandler
    public void tickEnded(ServerTickEndEvent event) {
        // Use the contact state after vanilla entity ticking, not the previous
        // tick's state seen by the scheduler or a movement event.
        for(Player player:Bukkit.getOnlinePlayers()) {
            returnFromLava(player);
            extinguishAfterLava(player);
        }
    }
    private void lavaContact(Player player) {
        lavaExtinguish.contact(player.getUniqueId());
        pendingLavaRetries.add(player.getUniqueId());
    }
    private void returnFromLava(Player player) {
        // Participant runs have their own fail handling; never jump to the builder CP.
        if(participants.active(player)) { pendingLavaRetries.remove(player.getUniqueId()); return; }
        UUID id=player.getUniqueId(); boolean contact=pendingLavaRetries.remove(id);
        if(player.getGameMode()!=GameMode.ADVENTURE || player.isDead()) {
            lavaRetryFailures.remove(id); return;
        }
        if(!contact && !player.isInLava()) return;
        lavaExtinguish.contact(id);
        player.setFireTicks(0);
        // Do not invent a checkpoint, clear blocks or spam the player if none exists.
        if(state.checkpoint(id)==null) return;
        int tick=Bukkit.getCurrentTick(); Integer lastFailure=lavaRetryFailures.get(id);
        if(lastFailure!=null && tick-lastFailure<20) return;
        // Retry only after vanilla has finished applying effects at the old
        // position. Teleporting inside a damage/ignite callback can be undone
        // by subsequent collision effects from that same movement.
        if(retry(player)) lavaRetryFailures.remove(id);
        else lavaRetryFailures.put(id,tick);
    }
    private void extinguishAfterLava(Player player) {
        boolean eligible=player.getGameMode()==GameMode.ADVENTURE && !player.isDead();
        if(lavaExtinguish.shouldExtinguish(player.getUniqueId(),eligible,eligible&&player.isInLava(),Bukkit.getCurrentTick()) && player.getFireTicks()>0) {
            player.setFireTicks(0);
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void combustedByBlock(EntityCombustByBlockEvent event) {
        if(!(event.getEntity() instanceof Player player) || player.getGameMode()!=GameMode.ADVENTURE || participants.active(player)) return;
        Block source=event.getCombuster();
        if((source!=null && source.getType()==Material.LAVA) || (source==null && player.isInLava())) {
            lavaContact(player);
            if(state.checkpoint(player.getUniqueId())!=null) event.setCancelled(true);
        } else if(source!=null) {
            // A subsequent fire block is a new source, not residual lava fire.
            lavaExtinguish.forget(player.getUniqueId());
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void lavaContactDamage(EntityDamageEvent event) {
        if(!(event.getEntity() instanceof Player player) || player.getGameMode()!=GameMode.ADVENTURE || participants.active(player)) return;
        if(event.getCause()==EntityDamageEvent.DamageCause.LAVA) {
            lavaContact(player);
            if(state.checkpoint(player.getUniqueId())!=null) event.setCancelled(true);
        } else if(event.getCause()==EntityDamageEvent.DamageCause.FIRE_TICK && lavaExtinguish.hasContact(player.getUniqueId()) && state.checkpoint(player.getUniqueId())!=null) {
            event.setCancelled(true);
        }
        else if(event.getCause()==EntityDamageEvent.DamageCause.FIRE) lavaExtinguish.forget(player.getUniqueId());
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void place(BlockPlaceEvent event) {
        if(tools.kind(event.getItemInHand())!=ToolItems.Kind.SCAFFOLD) return;
        Player player=event.getPlayer();
        if(!participants.active(player) && !lobby.managed(player) && !allowed(player)) { event.setCancelled(true); return; }
        try {
            state.add(new PlacedBlock(player.getUniqueId(),key(event.getBlockPlaced()),
                    event.getBlockReplacedState().getBlockData().getAsString(),event.getBlockPlaced().getBlockData().getAsString()));
            tools.refill(player); refillSoon(player);
        } catch(IOException|RuntimeException e) { event.setCancelled(true); failure(player,"editor.scaffold.record-failed",e); }
    }
    private void refillSoon(Player player) {
        if(!pendingRefills.add(player.getUniqueId())) return;
        Bukkit.getScheduler().runTask(this,() -> {
            pendingRefills.remove(player.getUniqueId());
            if(player.isOnline()) { tools.refill(player); player.updateInventory(); }
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void breakDrops(BlockBreakEvent event) {
        if(state.contains(key(event.getBlock()))) event.setDropItems(false);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void broken(BlockBreakEvent event) {
        if(!state.contains(key(event.getBlock()))) return;
        // Keep the durable entry until the block has actually been removed. If
        // shutdown occurs first, startup cleanup will restore the old state.
        BlockKey broken=key(event.getBlock());
        Bukkit.getScheduler().runTask(this,() -> {
            try { cleanup(state.allBlocks().stream().filter(b -> b.block().equals(broken)).toList()); }
            catch(IOException|RuntimeException e) { getLogger().log(Level.SEVERE,"Broken scaffold cleanup failed",e); }
        });
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        lavaExtinguish.forget(event.getPlayer().getUniqueId());
        pendingLavaRetries.remove(event.getPlayer().getUniqueId());
        lavaRetryFailures.remove(event.getPlayer().getUniqueId());
        lastUse.remove(event.getPlayer().getUniqueId());
        try { cleanup(state.blocks(event.getPlayer().getUniqueId())); }
        catch(IOException|RuntimeException e) { getLogger().log(Level.SEVERE,"Logout cleanup incomplete; journal retained",e); }
    }
    @EventHandler public void worldLoaded(WorldLoadEvent event) {
        try { cleanup(state.allBlocks().stream().filter(b -> b.block().world().equals(event.getWorld().getUID())).toList()); }
        catch(IOException|RuntimeException e) { getLogger().log(Level.SEVERE,"World recovery incomplete; journal retained",e); }
    }
    private int cleanup(List<PlacedBlock> entries) throws IOException {
        List<BlockKey> completed=new ArrayList<>(); int restored=0;
        for(PlacedBlock placed:entries) {
            BlockKey key=placed.block(); World world=Bukkit.getWorld(key.world()); if(world==null) continue;
            Block block=world.getBlockAt(key.x(),key.y(),key.z());
            // A builder may have subsequently edited this location. Do not
            // overwrite a different block with the old trial's snapshot.
            if(block.getBlockData().getAsString().equals(placed.after())) {
                block.setBlockData(Bukkit.createBlockData(placed.before()),false); restored++;
            }
            completed.add(key);
        }
        state.remove(completed); return restored;
    }
    private void failure(Player player,String key,Exception e) {
        tell(player,key); getLogger().log(Level.SEVERE,Msg.plain(player,key),e);
    }
}
