import java.lang.reflect.*;
import java.util.*;
import java.util.logging.Logger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.*;
import org.bukkit.event.block.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/** Actual Paper items/world, simulated player event dispatch. No real client is claimed. */
public class ServerChecks {
    static Object invoke(Object object,String name,Object... args) throws Exception {
        for(Method m:object.getClass().getDeclaredMethods()) if(m.getName().equals(name) && m.getParameterCount()==args.length) {
            boolean compatible=true;
            for(int i=0;i<args.length;i++) if(args[i]!=null && !m.getParameterTypes()[i].isInstance(args[i])) compatible=false;
            if(!compatible) continue;
            m.setAccessible(true); return m.invoke(object,args);
        }
        throw new NoSuchMethodException(name);
    }
    static Object field(Object object,String name) throws Exception { var f=object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
    static void require(boolean success,String message) { if(!success) throw new AssertionError(message); }
    static Object checkpoint(Object state,UUID id) throws Exception {
        var m=state.getClass().getDeclaredMethod("checkpoint",UUID.class); m.setAccessible(true); return m.invoke(state,id);
    }
    static List<?> blocks(Object state,UUID id) throws Exception { return (List<?>)invoke(state,"blocks",id); }
    static void click(Object plugin,SimPlayer p,ItemStack item,EquipmentSlot hand) throws Exception {
        var event=new PlayerInteractEvent(p.player,Action.RIGHT_CLICK_AIR,item,null,null,hand);
        event.setCancelled(true); // Vanilla's predictive air-click cancellation.
        invoke(plugin,"interact",event); require(event.isCancelled(),"Tool click was not consumed");
    }
    static void clearUse(Object plugin,UUID id) throws Exception { ((Map<?,?>)field(plugin,"lastUse")).remove(id); }
    static void cpDrop(Object plugin,SimPlayer p,ItemStack cp) throws Exception {
        Item drop=(Item)Proxy.newProxyInstance(Item.class.getClassLoader(),new Class[]{Item.class},(o,m,a) -> {
            if(m.getName().equals("getItemStack")) return cp;
            throw new UnsupportedOperationException(m.getName());
        });
        var event=new PlayerDropItemEvent(p.player,drop); invoke(plugin,"drop",event);
        require(event.isCancelled(),"CP item really dropped");
    }
    public static void run(Object plugin) throws Exception {
        Logger logger=((JavaPlugin)plugin).getLogger();
        Object state=field(plugin,"state"),tools=field(plugin,"tools");
        World world=Bukkit.getWorlds().getFirst(); Location spawn=world.getSpawnLocation();
        int x=spawn.getBlockX(),y=world.getMaxHeight()-8,z=spawn.getBlockZ()+4;
        Block floor=world.getBlockAt(x,y,z),own=world.getBlockAt(x,y+1,z),other=world.getBlockAt(x+1,y+1,z),plain=world.getBlockAt(x+2,y+1,z);
        List<Block> probes=List.of(floor,own,other,plain);
        for(Block b:probes) require(b.getType().isAir(),"Probe area is not empty; no edits made");
        List<BlockState> original=probes.stream().map(Block::getState).toList();
        SimPlayer a=new SimPlayer(new Location(world,x+.5,y+1,z+.5,42,-15)),b=new SimPlayer(new Location(world,x+.5,y+1,z+.5));
        try {
            floor.setType(Material.STONE,false);
            require((boolean)invoke(tools,"grant",a.player),"Kit failed");
            require((boolean)invoke(tools,"grant",a.player),"Repeated kit failed");
            ItemStack cp=a.inventory.getItem(0),scaffold=a.inventory.getItem(1),mode=a.inventory.getItem(2);
            require(cp.getType()==Material.AMETHYST_SHARD && cp.getAmount()==1,"Wrong checkpoint item");
            require(scaffold.getType()==Material.POLISHED_ANDESITE && scaffold.getAmount()==64,"Wrong scaffold item");
            require(mode.getType()==Material.SLIME_BALL && mode.getAmount()==1,"Wrong mode item");
            int tagged=0; for(int i=0;i<41;i++) if(invoke(tools,"kind",a.inventory.getItem(i))!=null) tagged++;
            require(tagged==3,"Repeated kit duplicated tools");
            var nativeStack=CraftItemStack.asNMSCopy(scaffold);
            for(Material material:List.of(Material.STONE,Material.POLISHED_ANDESITE,Material.CHEST,Material.GLASS,Material.BEDROCK)) {
                floor.setType(material,false);
                var target=new BlockInWorld(((CraftWorld)world).getHandle(),new BlockPos(x,y,z),true);
                require(nativeStack.canPlaceOnBlockInAdventureMode(target),"Adventure predicate rejects "+material);
                require(!CraftItemStack.asNMSCopy(new ItemStack(Material.POLISHED_ANDESITE)).canPlaceOnBlockInAdventureMode(target),"Ordinary block unexpectedly bypassed Adventure");
            }
            floor.setType(Material.STONE,false);
            for(Material surface:List.of(Material.STONE_SLAB,Material.OAK_FENCE)) {
                floor.setType(surface,false);
                Location foot=new Location(world,x+.5,y+(surface==Material.STONE_SLAB ? .5 : 1.5),z+.5);
                BoundingBox body=new BoundingBox(x+.2,foot.getY(),z+.2,x+.8,foot.getY()+1.8,z+.8);
                require((boolean)invoke(plugin,"hasPermanentSupport",foot,body),"Ground support rejected "+surface);
            }
            floor.setType(Material.STONE,false);
            cpDrop(plugin,a,cp); Object saved=checkpoint(state,a.id);
            require(saved!=null,"Real collision shape did not support CP");
            require(((Number)invoke(saved,"yaw")).floatValue()==42 && ((Number)invoke(saved,"pitch")).floatValue()==-15,"View not saved");
            a.ground=false; a.location.add(0,1,0); cpDrop(plugin,a,cp);
            require(checkpoint(state,a.id).equals(saved),"Airborne CP replaced saved CP");
            a.ground=true; cpDrop(plugin,a,cp);
            require(checkpoint(state,a.id).equals(saved),"Client ground flag alone allowed a floating CP");
            a.location=new Location(world,x+.5,y+1,z+.5); a.selected=1;
            BlockState before=own.getState(); own.setType(Material.POLISHED_ANDESITE,false);
            ItemStack oldHeld=a.inventory.getItem(1);
            invoke(plugin,"place",new BlockPlaceEvent(own,before,floor,oldHeld,a.player,true,EquipmentSlot.HAND));
            oldHeld.setAmount(63); // Native BlockItem may consume the old mirror after event.
            require(a.inventory.getItem(1).getAmount()==64,"Native-style consumption reduced the replacement stack");
            require(blocks(state,a.id).size()==1,"Dedicated placement not registered");
            a.location.setY(y+2); cpDrop(plugin,a,cp);
            require(checkpoint(state,a.id).equals(saved),"Temporary scaffold allowed CP");
            require((boolean)invoke(tools,"grant",b.player),"Second kit failed"); b.selected=1;
            BlockState otherBefore=other.getState(); other.setType(Material.POLISHED_ANDESITE,false);
            invoke(plugin,"place",new BlockPlaceEvent(other,otherBefore,floor,b.inventory.getItem(1),b.player,true,EquipmentSlot.HAND));
            BlockState plainBefore=plain.getState(); plain.setType(Material.POLISHED_ANDESITE,false);
            invoke(plugin,"place",new BlockPlaceEvent(plain,plainBefore,floor,new ItemStack(Material.POLISHED_ANDESITE),a.player,true,EquipmentSlot.HAND));
            require(blocks(state,a.id).size()==1,"Ordinary polished andesite was tracked");
            a.selected=2; clearUse(plugin,a.id); click(plugin,a,mode,EquipmentSlot.HAND);
            require(a.mode==GameMode.CREATIVE,"Mode did not switch to Creative");
            click(plugin,a,mode,EquipmentSlot.OFF_HAND); require(a.mode==GameMode.CREATIVE,"Offhand doubled mode toggle");
            clearUse(plugin,a.id); click(plugin,a,mode,EquipmentSlot.HAND); require(a.mode==GameMode.ADVENTURE,"Mode did not switch back");
            a.selected=0; a.teleportAllowed=false; clearUse(plugin,a.id); click(plugin,a,cp,EquipmentSlot.HAND);
            require(own.getType()==Material.POLISHED_ANDESITE && blocks(state,a.id).size()==1,"Cancelled teleport erased trial");
            a.teleportAllowed=true; a.velocity=new Vector(2,-3,1); a.fall=12; a.sprinting=true; a.flying=true;
            clearUse(plugin,a.id); click(plugin,a,cp,EquipmentSlot.HAND);
            require(own.getType().isAir() && blocks(state,a.id).isEmpty(),"Retry failed to remove owned scaffold");
            require(other.getType()==Material.POLISHED_ANDESITE && blocks(state,b.id).size()==1,"Retry erased another player's scaffold");
            require(plain.getType()==Material.POLISHED_ANDESITE,"Retry erased ordinary course block");
            require(a.location.getYaw()==42 && a.location.getPitch()==-15 && a.location.getY()==y+1,"Retry lost checkpoint view or position");
            require(a.velocity.lengthSquared()==0 && a.fall==0 && !a.flying && !a.sprinting,"Retry retained motion");
            require(a.inventory.getItem(1).getAmount()==64,"Retry kit count wrong");
            before=own.getState(); own.setType(Material.POLISHED_ANDESITE,false);
            invoke(plugin,"place",new BlockPlaceEvent(own,before,floor,a.inventory.getItem(1),a.player,true,EquipmentSlot.HAND));
            own.setType(Material.GOLD_BLOCK,false); clearUse(plugin,a.id); click(plugin,a,cp,EquipmentSlot.HAND);
            require(own.getType()==Material.GOLD_BLOCK && blocks(state,a.id).isEmpty(),"Cleanup overwrote a later builder edit");
            logger.info("INTEGRATION_PASS actual Paper items + wildcard Adventure predicate (5 materials), real collision support, simulated player drop/click/place events, CP view, infinite 64, mode/offhand, rejected TP, owner isolation, later edit preservation; no Minecraft-client gameplay tested");
        } finally {
            for(SimPlayer p:List.of(a,b)) {
                invoke(plugin,"cleanup",blocks(state,p.id)); invoke(state,"removeCheckpoint",p.id);
                clearUse(plugin,p.id); ((Set<?>)field(plugin,"pendingRefills")).remove(p.id);
            }
            for(BlockState snapshot:original) snapshot.update(true,false);
            for(Block probe:probes) require(probe.getType().isAir(),"Probe cleanup failed");
            logger.info("INTEGRATION_CLEANUP_PASS probe blocks restored; simulated player records removed");
        }
    }
    static final class SimPlayer {
        final UUID id=UUID.randomUUID(); final Player player; final PlayerInventory inventory;
        final Inventory backing=Bukkit.createInventory(null,54);
        Location location; int selected; boolean ground=true,teleportAllowed=true,flying,sprinting;
        float fall; Vector velocity=new Vector(); GameMode mode=GameMode.ADVENTURE;
        SimPlayer(Location location) {
            this.location=location;
            inventory=(PlayerInventory)Proxy.newProxyInstance(PlayerInventory.class.getClassLoader(),new Class[]{PlayerInventory.class},(proxy,m,args) -> {
                return switch(m.getName()) {
                    case "getSize" -> 41;
                    case "getItemInMainHand" -> backing.getItem(selected)==null ? new ItemStack(Material.AIR) : backing.getItem(selected);
                    case "getItemInOffHand" -> backing.getItem(40)==null ? new ItemStack(Material.AIR) : backing.getItem(40);
                    case "getHeldItemSlot" -> selected;
                    case "getContents" -> Arrays.copyOf(backing.getContents(),41);
                    case "getStorageContents" -> Arrays.copyOf(backing.getContents(),36);
                    case "getType" -> org.bukkit.event.inventory.InventoryType.PLAYER;
                    default -> backing.getClass().getMethod(m.getName(),m.getParameterTypes()).invoke(backing,args);
                };
            });
            player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class[]{Player.class},(proxy,m,args) -> {
                return switch(m.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> "GauntletIntegration";
                    case "getInventory" -> inventory;
                    case "hasPermission","isOp" -> true;
                    case "isOnline" -> false;
                    case "getLocation" -> this.location.clone();
                    case "getWorld" -> this.location.getWorld();
                    case "isOnGround" -> ground;
                    case "getBoundingBox" -> new BoundingBox(this.location.getX()-.3,this.location.getY(),this.location.getZ()-.3,this.location.getX()+.3,this.location.getY()+1.8,this.location.getZ()+.3);
                    case "getGameMode" -> mode;
                    case "setGameMode" -> { mode=(GameMode)args[0]; yield null; }
                    case "isFlying" -> flying;
                    case "setFlying" -> { flying=(boolean)args[0]; yield null; }
                    case "setSprinting" -> { sprinting=(boolean)args[0]; yield null; }
                    case "setFallDistance" -> { fall=(float)args[0]; yield null; }
                    case "setVelocity" -> { velocity=((Vector)args[0]).clone(); yield null; }
                    case "teleport" -> { if(teleportAllowed) this.location=((Location)args[0]).clone(); yield teleportAllowed; }
                    case "sendMessage","updateInventory","playSound" -> null;
                    case "toString" -> "SimPlayer["+id+"]";
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy==args[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                };
            });
        }
    }
}
