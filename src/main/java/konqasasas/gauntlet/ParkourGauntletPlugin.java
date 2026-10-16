package konqasasas.gauntlet;

import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import konqasasas.gauntlet.course.*;
import konqasasas.gauntlet.play.*;
import net.kyori.adventure.text.Component;
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
    private CourseEditor courses;
    private Participants participants;
    private final Map<UUID,Integer> lastUse=new HashMap<>();
    private final Set<UUID> pendingRefills=new HashSet<>();
    private final LavaExtinguishTracker lavaExtinguish=new LavaExtinguishTracker();
    private final Set<UUID> pendingLavaRetries=new HashSet<>();
    private final Map<UUID,Integer> lavaRetryFailures=new HashMap<>();

    @Override public void onEnable() {
        try {
            state=new StateStore(getDataFolder().toPath().resolve("state.json"));
            tools=new ToolItems(new NamespacedKey(this,"tool"));
            cleanup(state.allBlocks());
            getServer().getPluginManager().registerEvents(this,this);
            Objects.requireNonNull(getCommand("gauntlet")).setExecutor(this);
            getCommand("gauntlet").setTabCompleter(this);
            var data=getDataFolder().toPath();
            CourseStore courseStore=new CourseStore(data.resolve("courses"));
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
            };
            CourseItems courseItems=new CourseItems(templateStore,scaffold,new NamespacedKey(this,"course_item"));
            participants=new Participants(this,courseStore,new RecordStore(data.resolve("records.jsonl")),
                    new ReturnStore(data.resolve("participants.json")),scaffold,courseItems);
            participants.start();
            courses=new CourseEditor(this,courseStore,templateStore,courseItems,new NamespacedKey(this,"tool"),participants);
            courses.start();
            getLogger().info("Parkour Gauntlet creation tools ready: /gauntlet kit (Paper 26.3)");
        } catch(IOException|RuntimeException e) {
            getLogger().log(Level.SEVERE,"Cannot initialize creation tools; existing state was preserved",e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    @Override public void onDisable() {
        if(courses!=null) courses.stop();
        if(participants!=null) participants.stop();
        if(state!=null) try { cleanup(state.allBlocks()); }
        catch(IOException|RuntimeException e) { getLogger().log(Level.SEVERE,"Cleanup incomplete; journal retained for next start",e); }
        lastUse.clear(); pendingRefills.clear(); lavaExtinguish.clear();
        pendingLavaRetries.clear(); lavaRetryFailures.clear();
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!(sender instanceof Player player)) { sender.sendMessage("ゲーム内で /gauntlet kit を実行してください。"); return true; }
        if(!allowed(player)) return true;
        if(args.length!=1 || !args[0].equalsIgnoreCase("kit")) { tell(player,"/gauntlet kit で制作アイテムを受け取れます。"); return true; }
        if(!tools.grant(player)) tell(player,"インベントリに空きを作ってください。");
        else tell(player,"制作アイテムを渡しました。アメジストをQでCP設定、右クリックでリトライ。");
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        return args.length==1 && "kit".startsWith(args[0].toLowerCase(Locale.ROOT)) ? List.of("kit") : List.of();
    }
    private boolean allowed(Player player) {
        if(player.hasPermission("gauntlet.admin")) return true;
        tell(player,"このアイテムは運営用です。"); return false;
    }
    private void tell(Player player,String message) { player.sendMessage(Component.text("[Gauntlet] "+message)); }
    private static BlockKey key(Block block) { return new BlockKey(block.getWorld().getUID(),block.getX(),block.getY(),block.getZ()); }

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
        if(!player.isOnGround() || !hasPermanentSupport(location,player.getBoundingBox())) {
            tell(player,"CPは接地中の通常の足場で設定してください。設置用ブロックの上では設定できません。"); return;
        }
        try {
            state.checkpoint(player.getUniqueId(),new Checkpoint(location.getWorld().getUID(),location.getX(),location.getY(),location.getZ(),location.getYaw(),location.getPitch()));
            tell(player,"CPを設定しました。位置と視点を保存しました。");
            player.playSound(location,Sound.ENTITY_EXPERIENCE_ORB_PICKUP,SoundCategory.PLAYERS,1f,1f);
            getLogger().info("CP_SET player="+player.getUniqueId()+" world="+location.getWorld().getName()+" position="+location.getX()+","+location.getY()+","+location.getZ()+" yaw="+location.getYaw()+" pitch="+location.getPitch());
        } catch(IOException e) { failure(player,"CPを保存できませんでした。",e); }
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
            player.setGameMode(mode); tell(player,"ゲームモード："+mode.name());
            player.playSound(player.getLocation(),Sound.BLOCK_NOTE_BLOCK_PLING,SoundCategory.PLAYERS,1f,1f);
        }
    }
    private boolean retry(Player player) {
        Checkpoint cp=state.checkpoint(player.getUniqueId());
        if(cp==null) { tell(player,"CPが未設定です。接地中にアメジストをQで設定してください。"); return false; }
        World world=Bukkit.getWorld(cp.world());
        if(world==null) { tell(player,"CPのワールドが読み込まれていません。"); return false; }
        Location target=new Location(world,cp.x(),cp.y(),cp.z(),cp.yaw(),cp.pitch());
        int chunkX=target.getBlockX()>>4,chunkZ=target.getBlockZ()>>4;
        if(!world.isChunkLoaded(chunkX,chunkZ) && !world.loadChunk(chunkX,chunkZ,false)) {
            tell(player,"CPのチャンクを読み込めませんでした。"); return false;
        }
        // Never clear the course trial if another plugin rejects this teleport.
        if(!player.teleport(target,PlayerTeleportEvent.TeleportCause.PLUGIN)) { tell(player,"CPへの移動が拒否されました。"); return false; }
        stopMotion(player);
        try {
            cleanup(state.blocks(player.getUniqueId()));
            tools.refill(player); player.updateInventory();
        } catch(IOException|RuntimeException e) { failure(player,"足場の片付けが完了しませんでした。ログを確認してください。",e); }
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
        if(!participants.active(event.getPlayer()) && !allowed(event.getPlayer())) { event.setCancelled(true); return; }
        try {
            state.add(new PlacedBlock(event.getPlayer().getUniqueId(),key(event.getBlockPlaced()),
                    event.getBlockReplacedState().getBlockData().getAsString(),event.getBlockPlaced().getBlockData().getAsString()));
            tools.refill(event.getPlayer()); refillSoon(event.getPlayer());
        } catch(IOException|RuntimeException e) { event.setCancelled(true); failure(event.getPlayer(),"足場を記録できないため設置を拒否しました。",e); }
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
    private void failure(Player player,String message,Exception e) {
        tell(player,message); getLogger().log(Level.SEVERE,message,e);
    }
}
