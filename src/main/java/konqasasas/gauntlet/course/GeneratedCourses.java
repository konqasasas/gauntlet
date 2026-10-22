package konqasasas.gauntlet.course;

import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import konqasasas.gauntlet.i18n.Msg;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * /gauntlet generate <profile> [seed]: builds a course out of modules at the test
 * place (config generator.place) and saves it as the course gen_<profile>, playable
 * like any course. The previous course of that profile is cleared first.
 *
 * Copying: the boxes of the new modules are emptied, then every module's blocks
 * are copied in order (a later module's start block replaces the earlier goal
 * block), then the joints. Entities are not copied.
 */
public final class GeneratedCourses {
    private final JavaPlugin plugin;
    private final CourseStore store;
    public GeneratedCourses(JavaPlugin plugin,CourseStore store) { this.plugin=plugin; this.store=store; }

    public void generate(Player player,String profileName,Long seedArg) {
        ConfigurationSection config=plugin.getConfig().getConfigurationSection("generator");
        ConfigurationSection p=config==null ? null : config.getConfigurationSection("profiles."+profileName);
        if(p==null) { Msg.send(player,"editor.generate.no-profile",Msg.arg("profile",profileName)); return; }
        String id="gen_"+profileName.toLowerCase(Locale.ROOT);
        if(!CourseStore.validId(id)) { Msg.send(player,"editor.generate.no-profile",Msg.arg("profile",profileName)); return; }
        Location origin=parsePlace(config.getString("place",""));
        if(origin==null) { Msg.send(player,"editor.generate.no-place"); return; }
        World world=origin.getWorld();
        List<Integer> range=p.getIntegerList("difficulty");
        Generator.Profile profile;
        try { profile=new Generator.Profile(p.getInt("modules",5),range.isEmpty() ? 1 : range.getFirst(),range.isEmpty() ? 5 : range.getLast()); }
        catch(IllegalArgumentException e) { Msg.send(player,"editor.generate.bad-profile",Msg.arg("profile",profileName)); return; }
        int joint=config.getString("joint","none").equalsIgnoreCase("platform") ? Math.max(1,config.getInt("joint-length",3)) : 0;
        Generator.Settings settings=new Generator.Settings(config.getInt("hard-from",4),Math.max(0,config.getInt("max-hard-streak",2)),joint,world.getMinHeight(),world.getMaxHeight()-1);
        BlockData jointBlock;
        try { jointBlock=Bukkit.createBlockData(config.getString("joint-block","minecraft:smooth_stone")); }
        catch(IllegalArgumentException e) { jointBlock=Material.SMOOTH_STONE.createBlockData(); }
        long seed=seedArg!=null ? seedArg : new Random().nextLong();

        // The modules: courses marked as modules, complete, in a loaded world.
        Map<String,Course> sources=new LinkedHashMap<>();
        List<Generator.Piece> pieces=new ArrayList<>();
        for(Course c:store.all()) {
            if(c.module()==null || !c.problems().isEmpty() || !c.segments().isEmpty()) continue;
            World w=Bukkit.getWorld(c.world()); if(w==null) continue;
            Spot s=c.start();
            BlockPos start=new BlockPos((int)Math.floor(s.x()),(int)Math.floor(s.y()-.01),(int)Math.floor(s.z()));
            BlockPos exit=c.goals().stream().max(java.util.Comparator.comparingInt(BlockPos::z)).orElseThrow();
            pieces.add(new Generator.Piece(c.id(),c.module().difficulty(),start,exit,c.region(),solid(w,c.region())));
            sources.put(c.id(),c);
        }
        Generator.Plan plan=Generator.plan(pieces,profile,settings,new BlockPos(origin.getBlockX(),origin.getBlockY(),origin.getBlockZ()),seed);
        if(!plan.ok()) {
            Msg.send(player,"editor.generate.stuck",Msg.arg("index",plan.failedAt()+1),Msg.arg("difficulty",plan.target()),
                    Msg.arg("reason",Msg.text(player,"editor.generate.reason."+plan.failure())),Msg.arg("modules",pieces.size()));
            return;
        }
        // Clear the previous course of this profile, and the boxes the new one takes.
        Course previous=store.get(id);
        if(previous!=null && previous.region()!=null && Bukkit.getWorld(previous.world())!=null) fill(Bukkit.getWorld(previous.world()),previous.region(),Material.AIR.createBlockData());
        for(Generator.Placed placed:plan.placed()) fill(world,placed.region(),Material.AIR.createBlockData());
        for(Generator.Placed placed:plan.placed()) copy(Bukkit.getWorld(sources.get(placed.piece().id()).world()),world,placed.piece(),placed.offset());
        for(BlockPos j:plan.joints()) world.getBlockAt(j.x(),j.y(),j.z()).setBlockData(jointBlock,false);

        Course course=build(id,profileName,seed,player.getUniqueId(),world.getUID(),plan,sources,previous);
        try {
            if(previous==null) store.create(course);
            else store.save(course.sameGameplay(previous) ? course : course.edit().rev(previous.rev()+1).build());
        } catch(IOException|RuntimeException e) {
            Msg.send(player,"editor.generate.save-failed"); plugin.getLogger().log(Level.SEVERE,"Cannot save generated course "+id,e); return;
        }
        Msg.send(player,"editor.generate.done",Msg.arg("id",id),Msg.arg("count",plan.placed().size()),Msg.arg("seed",seed),
                Msg.arg("modules",String.join(" → ",plan.placed().stream().map(x -> x.piece().id()+"("+x.piece().difficulty()+")").toList())));
    }

    /** Everything of the modules moved into place, as one course. */
    private Course build(String id,String profile,long seed,UUID author,UUID world,Generator.Plan plan,Map<String,Course> sources,Course previous) {
        List<Segment> segments=new ArrayList<>();
        List<Trigger> triggers=new ArrayList<>(); List<RoutePoint> route=new ArrayList<>(); List<BlockPos> placeable=new ArrayList<>();
        Region box=null;
        for(Generator.Placed placed:plan.placed()) {
            Course moved=sources.get(placed.piece().id()).moved(placed.offset());
            Module m=sources.get(placed.piece().id()).module();
            segments.add(new Segment(placed.piece().id(),moved.region(),m.seconds(),moved.loadout(),m.counts()));
            for(Trigger t:moved.triggers()) if(triggers.stream().noneMatch(x -> x.pos().equals(t.pos()))) triggers.add(t);
            route.addAll(moved.route());
            placeable.addAll(moved.placeable());
            box=box==null ? moved.region() : union(box,moved.region());
        }
        for(BlockPos j:plan.joints()) box=union(box,new Region(j.x(),j.y(),j.z(),j.x(),j.y()+3,j.z()));
        Course first=sources.get(plan.placed().getFirst().piece().id()).moved(plan.placed().getFirst().offset());
        String name=("生成 "+profile+" #"+Long.toHexString(seed)).substring(0,Math.min(Course.MAX_NAME_LENGTH,("生成 "+profile+" #"+Long.toHexString(seed)).length()));
        Course base=previous!=null ? previous : Course.create(id,author,world);
        var b=base.edit().name(name).status(CourseStatus.DRAFT).region(world,box).start(first.start()).goals(List.of(plan.placed().getLast().exit()))
                .timeLimit(0).triggers(triggers).route(route.size()>Course.MAX_ROUTE_POINTS ? route.subList(0,Course.MAX_ROUTE_POINTS) : route)
                .placeable(placeable).lanes(List.of()).module(null).segments(segments).failLava(true).failOutside(true);
        for(int slot=0;slot<Course.COURSE_SLOTS;slot++) b.loadout(slot,first.loadout().get(slot));
        return b.build();
    }
    private static Region union(Region a,Region b) {
        return new Region(Math.min(a.minX(),b.minX()),Math.min(a.minY(),b.minY()),Math.min(a.minZ(),b.minZ()),
                Math.max(a.maxX(),b.maxX()),Math.max(a.maxY(),b.maxY()),Math.max(a.maxZ(),b.maxZ()));
    }
    private static Set<BlockPos> solid(World world,Region r) {
        Set<BlockPos> solid=new HashSet<>();
        for(int x=r.minX();x<=r.maxX();x++) for(int y=r.minY();y<=r.maxY();y++) for(int z=r.minZ();z<=r.maxZ();z++)
            if(!world.getBlockAt(x,y,z).getType().isAir()) solid.add(new BlockPos(x,y,z));
        return solid;
    }
    private static void fill(World world,Region r,BlockData data) {
        for(int x=r.minX();x<=r.maxX();x++) for(int y=r.minY();y<=r.maxY();y++) for(int z=r.minZ();z<=r.maxZ();z++) {
            Block block=world.getBlockAt(x,y,z);
            if(!block.getBlockData().equals(data)) block.setBlockData(data,false);
        }
    }
    /** The module's non-air blocks (block entities with their contents). */
    private static void copy(World from,World to,Generator.Piece piece,BlockPos d) {
        for(BlockPos c:piece.solid()) {
            Block source=from.getBlockAt(c.x(),c.y(),c.z()),target=to.getBlockAt(c.x()+d.x(),c.y()+d.y(),c.z()+d.z());
            if(source.getState(false) instanceof TileState) source.getState().copy(target.getLocation()).update(true,false);
            else target.setBlockData(source.getBlockData(),false);
        }
    }
    /** "world x y z": where the first module's start block goes. */
    private static Location parsePlace(String place) {
        String[] p=place.strip().split("\\s+");
        if(p.length!=4) return null;
        World world=Bukkit.getWorld(p[0]); if(world==null) return null;
        try { return new Location(world,Integer.parseInt(p[1]),Integer.parseInt(p[2]),Integer.parseInt(p[3])); }
        catch(NumberFormatException e) { return null; }
    }
}
