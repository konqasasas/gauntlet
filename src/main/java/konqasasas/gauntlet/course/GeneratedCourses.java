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
 * /module generate <profile> [seed]: builds a course out of modules at the master
 * place (for testing) and saves it as the course gen_<profile>, playable like any
 * course. Every gen_ course is cleared first: they all share the master place.
 * /module remove clears one. The match (startMatch) generates its courses at the
 * places of their profiles instead, as match_1, match_2, ... A course is never
 * generated over another generated course it does not replace.
 *
 * The course's start is its own (config generator.place, the block it stands on, and
 * start-yaw), not the first module's: the first module's start block goes at
 * start-offset from it. Both are whole blocks (a module's start is floored), so the
 * offset is whole too.
 *
 * Copying: the boxes of the new modules are emptied, then every module's blocks
 * are copied in order (a later module's start block replaces the earlier goal
 * block), then the joints. Entities are not copied.
 */
public final class GeneratedCourses {
    private final JavaPlugin plugin;
    private final CourseStore store;
    private konqasasas.gauntlet.data.ModuleTimes moduleTimes;
    public GeneratedCourses(JavaPlugin plugin,CourseStore store) { this.plugin=plugin; this.store=store; }
    public void moduleTimes(konqasasas.gauntlet.data.ModuleTimes times) { moduleTimes=times; }

    /**
     * Where and as what a course is generated: its id, name (null: from the profile and seed) and status,
     * the block its start stands on and the facing there. replaces: generated courses whose blocks it
     * takes over (cleared first; deleted when deleteReplaced, except the course of the same id).
     */
    private record Target(String id,String name,CourseStatus status,Location place,float yaw,java.util.function.Predicate<Course> replaces,boolean deleteReplaced) {}
    public static final String DEBUG_PREFIX="gen_",MATCH_PREFIX="match_";

    public void generate(Player player,String profileName,Long seedArg) { generate(player,profileName,seedArg,false); }
    /**
     * At the master place (generator.place), for testing: gen_<profile>, replacing the other gen_ courses.
     * measure: a course for measuring module times. No time limit, and only modules whose current
     * revision has fewer passes than generator.auto-time-passes (as many as the profile asks, or fewer),
     * by the profile's difficulty weights and bumpiness but without its bands or the hard streak.
     */
    public void generate(Player player,String profileName,Long seedArg,boolean measure) {
        ConfigurationSection config=plugin.getConfig().getConfigurationSection("generator");
        String id=DEBUG_PREFIX+profileName.toLowerCase(Locale.ROOT);
        if(!CourseStore.validId(id)) { Msg.send(player,"editor.generate.no-profile",Msg.arg("profile",profileName)); return; }
        Location place=config==null ? null : parsePlace(config.getString("place",""));
        if(place==null) { Msg.send(player,"editor.generate.no-place"); return; }
        generate(player,profileName,seedArg,measure,new Target(id,null,CourseStatus.DRAFT,place,(float)config.getDouble("start-yaw",0),
                c -> c.id().startsWith(DEBUG_PREFIX),true));
    }
    /** The id of course n (from 1) of the match. */
    public static String matchId(int n) { return MATCH_PREFIX+n; }
    /** n of a match course's id, or 0. */
    public static int matchIndex(String id) {
        if(id==null || !id.startsWith(MATCH_PREFIX)) return 0;
        try { return Integer.parseInt(id.substring(MATCH_PREFIX.length())); } catch(NumberFormatException e) { return 0; }
    }
    /**
     * Starts the match: the profiles of generator.match.courses, in order, each generated at its own place
     * (profiles.<name>.place) as course 1, 2, ... (match_1, match_2, ...), open to everyone. Match courses
     * beyond the count are cleared and closed. The definitions are kept, so a new match is a new revision
     * and clears of an earlier match do not open the next course. Stops at the first course that cannot be made.
     */
    public void startMatch(Player player) {
        ConfigurationSection config=plugin.getConfig().getConfigurationSection("generator");
        List<String> order=config==null ? List.of() : config.getStringList("match.courses");
        if(order.isEmpty()) { Msg.send(player,"match.no-courses"); return; }
        List<Location> places=new ArrayList<>();
        for(String name:order) {
            ConfigurationSection p=config.getConfigurationSection("profiles."+name);
            if(p==null) { Msg.send(player,"editor.generate.no-profile",Msg.arg("profile",name)); return; }
            Location place=parsePlace(p.getString("place",""));
            if(place==null) { Msg.send(player,"match.no-place",Msg.arg("profile",name)); return; }
            places.add(place);
        }
        for(int i=0;i<order.size();i++) {
            String id=matchId(i+1);
            ConfigurationSection p=config.getConfigurationSection("profiles."+order.get(i));
            Course made=generate(player,order.get(i),null,false,new Target(id,Msg.plain(player,"match.course-name",Msg.arg("n",i+1)),CourseStatus.DONE,
                    places.get(i),(float)p.getDouble("start-yaw",0),c -> c.id().equals(id),false));
            if(made==null) { Msg.send(player,"match.stopped",Msg.arg("n",i+1)); return; }
        }
        closeMatch(order.size());
        for(Player online:Bukkit.getOnlinePlayers()) Msg.send(online,"match.started",Msg.arg("count",order.size()));
    }
    /** Ends the match: every match course cleared and closed (definitions kept). */
    public void endMatch(Player player) {
        closeMatch(0);
        Msg.send(player,"match.ended");
    }
    /** Match courses after the first keep: cleared and closed. */
    private void closeMatch(int keep) {
        for(Course c:store.all()) {
            if(matchIndex(c.id())<=keep || c.status()==CourseStatus.DRAFT) continue;
            clear(c);
            try { store.save(c.withStatus(CourseStatus.DRAFT)); }
            catch(IOException|RuntimeException e) { plugin.getLogger().log(Level.WARNING,"Cannot close match course "+c.id(),e); }
        }
    }
    /** The match courses open now, in order (course 1 first). */
    public List<Course> matchCourses() {
        List<Course> open=new ArrayList<>();
        for(int n=1;;n++) {
            Course c=store.get(matchId(n));
            if(c==null || c.status()!=CourseStatus.DONE) return open;
            open.add(c);
        }
    }

    /** Generates the profile's course as the target; the course saved, or null (the player was told why). */
    private Course generate(Player player,String profileName,Long seedArg,boolean measure,Target target) {
        ConfigurationSection config=plugin.getConfig().getConfigurationSection("generator");
        ConfigurationSection p=config==null ? null : config.getConfigurationSection("profiles."+profileName);
        if(p==null) { Msg.send(player,"editor.generate.no-profile",Msg.arg("profile",profileName)); return null; }
        String id=target.id();
        Location place=target.place();
        BlockPos startOffset=parseOffset(config.getString("start-offset","0 0 0"));
        if(startOffset==null) { Msg.send(player,"editor.generate.bad-offset"); return null; }
        World world=place.getWorld();
        BlockPos startBlock=new BlockPos(place.getBlockX(),place.getBlockY(),place.getBlockZ());
        Spot courseStart=new Spot(startBlock.x()+.5,startBlock.y()+1,startBlock.z()+.5,target.yaw(),0);
        Generator.Profile profile;
        try { profile=profile(p); }
        catch(IllegalArgumentException e) { Msg.send(player,"editor.generate.bad-profile",Msg.arg("profile",profileName)); return null; }
        int joint=config.getString("joint","none").equalsIgnoreCase("platform") ? Math.max(1,config.getInt("joint-length",3)) : 0;
        int streak=measure ? Integer.MAX_VALUE : Math.max(0,config.getInt("max-hard-streak",2));
        Generator.Settings settings=new Generator.Settings(config.getInt("hard-from",4),streak,joint,world.getMinHeight(),world.getMaxHeight()-1);
        BlockData jointBlock;
        try { jointBlock=Bukkit.createBlockData(config.getString("joint-block","minecraft:smooth_stone")); }
        catch(IllegalArgumentException e) { jointBlock=Material.SMOOTH_STONE.createBlockData(); }
        long seed=seedArg!=null ? seedArg : new Random().nextLong();

        // The modules: courses marked as modules, complete, in a loaded world.
        Map<String,Course> sources=new LinkedHashMap<>();
        List<Generator.Piece> pieces=new ArrayList<>();
        for(Course c:store.all()) {
            if(c.module()==null || !c.problems().isEmpty() || !c.segments().isEmpty()) continue;
            if(measure && moduleTimes!=null && moduleTimes.passes(c.id(),c.rev())>=Math.max(1,config.getInt("auto-time-passes",5))) continue;
            World w=Bukkit.getWorld(c.world()); if(w==null) continue;
            Spot s=c.start();
            BlockPos start=new BlockPos((int)Math.floor(s.x()),(int)Math.floor(s.y()-.01),(int)Math.floor(s.z()));
            BlockPos exit=c.goals().stream().max(java.util.Comparator.comparingInt(BlockPos::z)).orElseThrow();
            pieces.add(new Generator.Piece(c.id(),c.module().difficulty(),start,exit,c.region(),solid(w,c.region())));
            sources.put(c.id(),c);
        }
        if(measure) {
            if(pieces.isEmpty()) { Msg.send(player,"editor.generate.measured-all",Msg.arg("passes",Math.max(1,config.getInt("auto-time-passes",5)))); return null; }
            profile=new Generator.Profile(Math.min(profile.modules(),pieces.size()),profile.weights(),profile.bumpiness());
        }
        Generator.Plan plan;
        try { plan=Generator.plan(pieces,profile,settings,startBlock.plus(startOffset),seed); }
        catch(Generator.BandConflict e) { Msg.send(player,"editor.generate.band-conflict",Msg.arg("profile",profileName),Msg.arg("band",GeneratorMenus.describe(player,e.band))); return null; }
        if(!plan.ok()) {
            Msg.send(player,"editor.generate.stuck",Msg.arg("index",plan.failedAt()+1),Msg.arg("difficulty",plan.target()),
                    Msg.arg("reason",Msg.text(player,"editor.generate.reason."+plan.failure())),Msg.arg("modules",pieces.size()));
            return null;
        }
        // Another generated course (one this does not replace, and not a closed match course) where this one would go: nothing is changed.
        Region box=box(plan,startBlock);
        for(Course other:store.all()) {
            if(other.segments().isEmpty() || other.region()==null || target.replaces().test(other)) continue;
            if(matchIndex(other.id())>0 && other.status()==CourseStatus.DRAFT) continue;
            if(other.world().equals(world.getUID()) && overlap(box,other.region())) { Msg.send(player,"editor.generate.overlap",Msg.arg("id",id),Msg.arg("other",other.id())); return null; }
        }
        // Clear the courses this replaces (the one of the same id is kept as a definition, for its records), and the boxes the new one takes.
        Course previous=store.get(id);
        for(Course old:store.all()) {
            if(old.segments().isEmpty() || !target.replaces().test(old)) continue;
            clear(old);
            if(target.deleteReplaced() && !old.id().equals(id)) try { store.delete(old.id()); } catch(IOException e) { plugin.getLogger().log(Level.WARNING,"Cannot delete generated course "+old.id(),e); }
        }
        for(Generator.Placed placed:plan.placed()) fill(world,placed.region(),Material.AIR.createBlockData());
        // Something to stand on at the course's start (the first module's start block when there is no offset).
        world.getBlockAt(startBlock.x(),startBlock.y(),startBlock.z()).setBlockData(jointBlock,false);
        for(Generator.Placed placed:plan.placed()) copy(Bukkit.getWorld(sources.get(placed.piece().id()).world()),world,pieces.stream().filter(x -> x.id().equals(placed.piece().id())).findFirst().orElseThrow(),placed);
        for(BlockPos j:plan.joints()) world.getBlockAt(j.x(),j.y(),j.z()).setBlockData(jointBlock,false);

        Course course=build(target,profileName,seed,player.getUniqueId(),world.getUID(),plan,sources,previous,courseStart,box,Math.max(0,config.getInt("start-ticks",0)),Math.max(0,p.getDouble("buffer-percent",config.getDouble("buffer-percent",10))),measure);
        try {
            if(previous==null) store.create(course);
            else store.save(course.sameGameplay(previous) ? course : course.edit().rev(previous.rev()+1).build());
        } catch(IOException|RuntimeException e) {
            Msg.send(player,"editor.generate.save-failed"); plugin.getLogger().log(Level.SEVERE,"Cannot save generated course "+id,e); return null;
        }
        int total=course.segments().stream().mapToInt(Segment::ticks).sum();
        Msg.send(player,measure ? "editor.generate.done-measure" : "editor.generate.done",Msg.arg("id",id),Msg.arg("count",plan.placed().size()),Msg.arg("seed",seed),
                Msg.arg("limit",konqasasas.gauntlet.play.RunClock.seconds(course.limitTicks())),Msg.arg("total",konqasasas.gauntlet.play.RunClock.seconds(total)),
                Msg.arg("modules",String.join(" → ",plan.placed().stream().map(x -> x.piece().id()+"("+x.piece().difficulty()+")").toList())));
        return store.get(id);
    }
    /**
     * Everything a planned course takes: its modules, the joints, the start block and the 3 blocks
     * above it, and past the goal (the last goal block and the 3 blocks beyond it, +Z) so running on stays inside.
     */
    private static Region box(Generator.Plan plan,BlockPos startBlock) {
        Region box=null;
        for(Generator.Placed placed:plan.placed()) box=box==null ? placed.region() : union(box,placed.region());
        for(BlockPos j:plan.joints()) box=union(box,new Region(j.x(),j.y(),j.z(),j.x(),j.y()+3,j.z()));
        box=union(box,new Region(startBlock.x(),startBlock.y(),startBlock.z(),startBlock.x(),startBlock.y()+3,startBlock.z()));
        BlockPos goal=plan.placed().getLast().exit();
        return union(box,new Region(goal.x(),goal.y(),goal.z(),goal.x(),goal.y()+3,goal.z()+3));
    }
    private static boolean overlap(Region a,Region b) {
        return a.minX()<=b.maxX() && b.minX()<=a.maxX() && a.minY()<=b.maxY() && b.minY()<=a.maxY() && a.minZ()<=b.maxZ() && b.minZ()<=a.maxZ();
    }

    /** Everything of the modules moved into place, as one course. */
    private Course build(Target target,String profile,long seed,UUID author,UUID world,Generator.Plan plan,Map<String,Course> sources,Course previous,
                         Spot start,Region box,int startTicks,double bufferPercent,boolean measure) {
        List<Segment> segments=new ArrayList<>();
        List<Trigger> triggers=new ArrayList<>(); List<RoutePoint> route=new ArrayList<>(); List<BlockPos> placeable=new ArrayList<>();
        for(Generator.Placed placed:plan.placed()) {
            Course moved=asPlaced(sources.get(placed.piece().id()),placed);
            Course source=sources.get(placed.piece().id()); Module m=source.module();
            segments.add(new Segment(placed.piece().id(),moved.region(),m.ticks(),moved.loadout(),m.counts(),source.rev()));
            for(Trigger t:moved.triggers()) if(triggers.stream().noneMatch(x -> x.pos().equals(t.pos()))) triggers.add(t);
            route.addAll(moved.route());
            placeable.addAll(moved.placeable());
        }
        Course first=asPlaced(sources.get(plan.placed().getFirst().piece().id()),plan.placed().getFirst());
        String full=target.name()!=null ? target.name() : (measure ? "計測 " : "生成 ")+profile+" #"+Long.toHexString(seed);
        String name=full.substring(0,Math.min(Course.MAX_NAME_LENGTH,full.length()));
        Course base=previous!=null ? previous : Course.create(target.id(),author,world);
        var b=base.edit().name(name).status(target.status()).region(world,box).start(start).goals(List.of(plan.placed().getLast().exit()))
                .timeLimit(0).triggers(triggers).route(route.size()>Course.MAX_ROUTE_POINTS ? route.subList(0,Course.MAX_ROUTE_POINTS) : route)
                .placeable(placeable).lanes(List.of()).module(null).segments(segments).limitTicks(measure ? 0 : Segment.limit(segments,startTicks,bufferPercent)).failLava(true).failOutside(true);
        for(int slot=0;slot<Course.COURSE_SLOTS;slot++) b.loadout(slot,first.loadout().get(slot));
        return b.build();
    }
    /**
     * A profile from config: modules, distribution (weights of difficulties 1-5), bumpiness and
     * bands ("4-5:1-2": difficulties 4-5 at least 1, at most 2 times).
     * Profiles written before distributions existed (difficulty: [from, to]) weigh that range evenly.
     */
    static Generator.Profile profile(ConfigurationSection p) {
        List<Double> weights=p.getDoubleList("distribution");
        if(weights.isEmpty()) {
            List<Integer> range=p.getIntegerList("difficulty");
            int from=range.isEmpty() ? 1 : range.getFirst(),to=range.isEmpty() ? 5 : range.getLast();
            weights=new ArrayList<>();
            for(int d=Module.MIN_DIFFICULTY;d<=Module.MAX_DIFFICULTY;d++) weights.add(d>=Math.min(from,to) && d<=Math.max(from,to) ? 1.0 : 0.0);
        }
        List<Generator.Band> bands=new ArrayList<>();
        for(String band:p.getStringList("bands")) bands.add(Generator.Band.parse(band));
        return new Generator.Profile(p.getInt("modules",5),weights,p.getDouble("bumpiness",1),bands);
    }
    /** The module's definition as placed: mirrored around its start column when it was, then moved. */
    private static Course asPlaced(Course module,Generator.Placed placed) {
        return (placed.mirrored() ? module.mirrorX(placed.piece().start().x()) : module).moved(placed.offset());
    }
    /** /module remove: clears the generated course of the profile and deletes it. */
    public void remove(Player player,String profile) {
        String id=profile.toLowerCase(Locale.ROOT); if(!id.startsWith(DEBUG_PREFIX)) id=DEBUG_PREFIX+id;
        Course course=store.get(id);
        if(course==null || course.segments().isEmpty()) { Msg.send(player,"editor.modules.not-generated",Msg.arg("profile",profile)); return; }
        clear(course);
        try { store.delete(id); }
        catch(IOException e) { Msg.send(player,"editor.generate.save-failed"); plugin.getLogger().log(Level.SEVERE,"Cannot delete "+id,e); return; }
        Msg.send(player,"editor.modules.removed",Msg.arg("id",id));
    }
    /** Every block of a generated course back to air. */
    private static void clear(Course course) {
        World world=course.region()==null ? null : Bukkit.getWorld(course.world());
        if(world!=null) fill(world,course.region(),Material.AIR.createBlockData());
    }
    private static Region union(Region a,Region b) {
        return new Region(Math.min(a.minX(),b.minX()),Math.min(a.minY(),b.minY()),Math.min(a.minZ(),b.minZ()),
                Math.max(a.maxX(),b.maxX()),Math.max(a.maxY(),b.maxY()),Math.max(a.maxZ(),b.maxZ()));
    }
    private static Set<BlockPos> solid(World world,Region r) {
        Set<BlockPos> solid=new HashSet<>();
        for(int x=r.minX();x<=r.maxX();x++) for(int y=r.minY();y<=r.maxY();y++) for(int z=r.minZ();z<=r.maxZ();z++)
        {
            // Glass marks a module's box while building: it is never copied.
            Material type=world.getBlockAt(x,y,z).getType();
            if(!type.isAir() && type!=Material.GLASS) solid.add(new BlockPos(x,y,z));
        }
        return solid;
    }
    private static void fill(World world,Region r,BlockData data) {
        for(int x=r.minX();x<=r.maxX();x++) for(int y=r.minY();y<=r.maxY();y++) for(int z=r.minZ();z<=r.maxZ();z++) {
            Block block=world.getBlockAt(x,y,z);
            if(!block.getBlockData().equals(data)) block.setBlockData(data,false);
        }
    }
    /** The module's non-air blocks (block entities with their contents); turned the other way when mirrored. */
    private static void copy(World from,World to,Generator.Piece built,Generator.Placed placed) {
        int axis=built.start().x(); BlockPos d=placed.offset();
        for(BlockPos c:built.solid()) {
            BlockPos at=(placed.mirrored() ? c.mirrorX(axis) : c).plus(d);
            Block source=from.getBlockAt(c.x(),c.y(),c.z()),target=to.getBlockAt(at.x(),at.y(),at.z());
            BlockData data=source.getBlockData().clone();
            if(placed.mirrored()) data.mirror(org.bukkit.block.structure.Mirror.FRONT_BACK);
            if(source.getState(false) instanceof TileState) {
                BlockState state=source.getState().copy(target.getLocation());
                state.setBlockData(data);
                state.update(true,false);
            } else target.setBlockData(data,false);
        }
    }
    /** "dx dy dz" (whole blocks): from the course's start block to the first module's start block; null when malformed. */
    static BlockPos parseOffset(String offset) {
        String[] p=offset.strip().split("\s+");
        if(p.length!=3) return null;
        try { return new BlockPos(Integer.parseInt(p[0]),Integer.parseInt(p[1]),Integer.parseInt(p[2])); }
        catch(NumberFormatException e) { return null; }
    }
    /** "world x y z": the block the course's start stands on. */
    private static Location parsePlace(String place) {
        String[] p=place.strip().split("\\s+");
        if(p.length!=4) return null;
        World world=Bukkit.getWorld(p[0]); if(world==null) return null;
        try { return new Location(world,Integer.parseInt(p[1]),Integer.parseInt(p[2]),Integer.parseInt(p[3])); }
        catch(NumberFormatException e) { return null; }
    }
}
