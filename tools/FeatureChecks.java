import java.lang.reflect.*;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Course item and trigger-mob behaviour on the real Paper server. No client or player is involved. */
public class FeatureChecks {
    static Object field(Object object,String name) throws Exception { var f=object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
    static Object call(Object target,String name,Object... args) throws Exception {
        Class<?> type=target instanceof Class<?> c ? c : target.getClass();
        for(Method m:type.getDeclaredMethods()) if(m.getName().equals(name) && m.getParameterCount()==args.length) {
            boolean ok=true;
            for(int i=0;i<args.length;i++) if(args[i]!=null && !wrap(m.getParameterTypes()[i]).isInstance(args[i])) ok=false;
            if(!ok) continue;
            m.setAccessible(true); return m.invoke(target instanceof Class<?> ? null : target,args);
        }
        throw new NoSuchMethodException(name);
    }
    static Class<?> wrap(Class<?> c) { return c==int.class ? Integer.class : c==boolean.class ? Boolean.class : c; }
    static void require(boolean success,String message) { if(!success) throw new AssertionError(message); }

    public static void run(Object plugin) throws Exception {
        Logger logger=((JavaPlugin)plugin).getLogger();
        Object participants=field(plugin,"participants"),items=field(participants,"items"),mobs=field(participants,"mobs");
        Object templates=field(field(plugin,"courses"),"templates");
        ClassLoader loader=plugin.getClass().getClassLoader();
        Class<?> refType=Class.forName("konqasasas.gauntlet.course.ItemRef",true,loader);
        Class<?> specType=Class.forName("konqasasas.gauntlet.course.MobSpec",true,loader);
        Class<?> spotType=Class.forName("konqasasas.gauntlet.course.Spot",true,loader);
        String templateId=null; List<UUID> spawned=new ArrayList<>(); Chunk chunk=null;
        try {
            // Leftovers from before the restart (tagged, untracked) must already be gone from loaded chunks.
            @SuppressWarnings("unchecked") Set<UUID> tracked=(Set<UUID>)field(mobs,"tracked");
            long leftovers=Bukkit.getWorlds().stream().flatMap(w -> w.getEntities().stream())
                    .filter(e -> e.getScoreboardTags().contains("gauntlet_mob") && !tracked.contains(e.getUniqueId())).count();
            require(leftovers==0,"Untracked course mobs remain: "+leftovers);
            String encoded=(String)call(items.getClass(),"encode",new ItemStack(Material.MACE));
            Object template=call(templates,"add","__feature_check_mace",encoded,true);
            templateId=(String)call(template,"id");
            Object level3=refType.getConstructor(String.class,int.class).newInstance(templateId,3);
            Object level1=refType.getConstructor(String.class,int.class).newInstance(templateId,1);
            ItemStack mace=(ItemStack)call(items,"create",level3);
            require(mace.getType()==Material.MACE && mace.getEnchantmentLevel(Enchantment.WIND_BURST)==3,"Wind Burst III missing");
            require(mace.getItemMeta().isUnbreakable(),"Template unbreakable flag not applied");
            require(level3.equals(call(items,"ref",mace)),"Course item tag does not round-trip");
            require(!level1.equals(call(items,"ref",mace)),"Different level matched");
            require((boolean)call(items,"isMace",templateId),"Mace template not detected");
            require(call(items,"ref",new ItemStack(Material.MACE))==null,"Plain mace recognised as course item");
            ItemStack scaffold=(ItemStack)call(items,"create",call(refType,"scaffold"));
            require(scaffold.getType()==Material.POLISHED_ANDESITE && call(items,"ref",scaffold).equals(call(refType,"scaffold")),"Scaffold ref broken");

            World world=Bukkit.getWorlds().getFirst(); Location spawn=world.getSpawnLocation();
            double x=spawn.getBlockX()+.5,y=world.getMaxHeight()-6,z=spawn.getBlockZ()+8.5;
            // With nobody online the probe chunk is not loaded; summoned mobs would not stay in the world.
            chunk=world.getChunkAt((int)Math.floor(x)>>4,(int)Math.floor(z)>>4); chunk.addPluginChunkTicket((JavaPlugin)plugin);
            Object spot=spotType.getConstructor(double.class,double.class,double.class,float.class,float.class).newInstance(x,y,z,0f,0f);
            Object spec=call(specType,"parse","summon minecraft:strider -84 -56 -37 {Age:0,Passengers:[],Health:1,Silent:1b,attributes:[{id:\"minecraft:movement_speed\",base:0}]}");
            require(spec!=null,"Pasted summon command rejected");
            @SuppressWarnings("unchecked") List<UUID> first=(List<UUID>)call(mobs,"spawn",world,spot,spec); spawned.addAll(first);
            if(first.size()!=1) {
                StringBuilder found=new StringBuilder();
                for(Entity e:world.getEntitiesByClass(Strider.class)) found.append(e.getLocation().toVector()).append(e.getScoreboardTags()).append(e.isValid()).append(';');
                throw new AssertionError("Expected one strider, got "+first.size()+" at "+x+","+y+","+z+" striders="+found);
            }
            Entity strider=Bukkit.getEntity(first.getFirst());
            require(strider instanceof Strider s && s.getHealth()==1 && s.isSilent(),"SNBT not applied");
            require(strider.getScoreboardTags().contains("gauntlet_mob") && !strider.isPersistent(),"Mob not tagged/non-persistent");
            require((boolean)call(mobs,"anyAlive",first),"Spawned mob not alive");
            List<?> saved=(List<?>)call(mobs,"snapshot",first);
            require(saved.size()==1,"Snapshot missing");
            call(mobs,"remove",first);
            require(!strider.isValid() && !(boolean)call(mobs,"anyAlive",first),"Mob not removed");
            @SuppressWarnings("unchecked") List<UUID> again=(List<UUID>)call(mobs,"respawn",saved); spawned.addAll(again);
            require(again.size()==1 && Bukkit.getEntity(again.getFirst()) instanceof Strider r && r.getHealth()==1,"Snapshot respawn failed");
            Object rider=call(specType,"parse","minecraft:strider {Passengers:[{id:\"minecraft:zombie\",IsBaby:1b}]}");
            @SuppressWarnings("unchecked") List<UUID> ridden=(List<UUID>)call(mobs,"spawn",world,spot,rider); spawned.addAll(ridden);
            require(ridden.size()==2,"Passenger not tracked: "+ridden.size());
            Object cart=call(specType,"parse","minecraft:minecart {Passengers:[{id:\"minecraft:happy_ghast\",Silent:1b,NoAI:1b}]}");
            @SuppressWarnings("unchecked") List<UUID> carted=(List<UUID>)call(mobs,"spawn",world,spot,cart); spawned.addAll(carted);
            require(carted.size()==2 && Bukkit.getEntity(carted.getFirst()) instanceof Minecart,"Vehicle with passenger not tracked: "+carted.size());
            call(mobs,"remove",new ArrayList<>(spawned));
            for(UUID id:spawned) require(Bukkit.getEntity(id)==null || !Bukkit.getEntity(id).isValid(),"Leftover mob "+id);
            // Real translation engine call (network). Uses a few characters of the free quota.
            Object service=field(field(plugin,"chat"),"service");
            if((boolean)call(service,"enabled")) {
                var future=(java.util.concurrent.CompletableFuture<?>)call(service,"translate","hello everyone, let's play together","ja");
                Object result=future.get(15,java.util.concurrent.TimeUnit.SECONDS);
                String translated=(String)call(result,"text"),source=(String)call(result,"source");
                require(!translated.isBlank() && !translated.contains("hello") && source.startsWith("en"),"Unexpected translation: "+translated+" / "+source);
                logger.info("FEATURE_TRANSLATE engine="+call(service,"engine")+" result="+translated);
            }
            logger.info("FEATURE_PASS template mace Wind Burst/unbreakable/tag, scaffold ref, pasted summon SNBT, mob tag/remove/snapshot respawn, passengers tracked, minecart vehicle tracked, no untracked leftovers");
        } finally {
            try { call(mobs,"remove",new ArrayList<>(spawned)); } catch(Exception ignored) {}
            if(templateId!=null) call(templates,"remove",templateId);
            if(chunk!=null) chunk.removePluginChunkTicket((JavaPlugin)plugin);
            logger.info("FEATURE_CLEANUP_PASS check template and mobs removed");
        }
    }
}
