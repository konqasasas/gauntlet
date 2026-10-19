package konqasasas.gauntlet.course;

import java.util.*;

/**
 * One course definition. Region and start stay null until the builder sets them.
 * loadout[i] is hotbar slot i+1 (null = empty). Fields added later are null in
 * older files and get their defaults here.
 *
 * rev counts edits that change how the course plays (records are kept per rev).
 * route is the line used for the progress percentage. placeable lists the only
 * cells where blocks may be placed while playing (empty = anywhere). Fields of older files that
 * no longer exist (rank, stage) are ignored.
 */
public record Course(String id,String name,CourseStatus status,UUID author,UUID world,Region region,Spot start,
                     List<BlockPos> goals,int timeLimitSeconds,List<ItemRef> loadout,List<Trigger> triggers,
                     Boolean failLava,Boolean failOutside,Integer rev,List<RoutePoint> route,List<BlockPos> placeable) {
    public static final int DEFAULT_TIME_LIMIT=30,MAX_TIME_LIMIT=600,MAX_NAME_LENGTH=32,COURSE_SLOTS=3,MAX_ROUTE_POINTS=200;
    public Course {
        if(!CourseStore.validId(id) || name==null || name.isBlank() || name.length()>MAX_NAME_LENGTH
                || status==null || author==null || world==null || timeLimitSeconds<0 || timeLimitSeconds>MAX_TIME_LIMIT)
            throw new IllegalArgumentException("Invalid course "+id);
        goals=goals==null ? List.of() : List.copyOf(new LinkedHashSet<>(goals));
        if(loadout==null) { loadout=new ArrayList<>(Collections.nCopies(COURSE_SLOTS,null)); loadout.set(0,ItemRef.scaffold()); }
        // Courses saved with four item slots: the fourth slot no longer exists.
        List<ItemRef> slots=new ArrayList<>(loadout);
        while(slots.size()>COURSE_SLOTS) slots.removeLast();
        while(slots.size()<COURSE_SLOTS) slots.add(null);
        loadout=Collections.unmodifiableList(slots);
        triggers=triggers==null ? List.of() : List.copyOf(triggers);
        if(triggers.stream().map(Trigger::pos).distinct().count()!=triggers.size()) throw new IllegalArgumentException("Duplicate trigger");
        failLava=failLava==null || failLava;
        failOutside=failOutside==null || failOutside;
        rev=rev==null ? 0 : rev;
        if(rev<0) throw new IllegalArgumentException("Negative revision");
        route=route==null ? List.of() : List.copyOf(route);
        if(route.size()>MAX_ROUTE_POINTS) throw new IllegalArgumentException("Route too long");
        placeable=placeable==null ? List.of() : List.copyOf(new LinkedHashSet<>(placeable));
    }
    public static Course create(String id,UUID author,UUID world) {
        return new Course(id,id,CourseStatus.DRAFT,author,world,null,null,null,DEFAULT_TIME_LIMIT,null,null,null,null,0,null,null);
    }

    public Builder edit() { return new Builder(this); }
    public static final class Builder {
        private final Course base;
        private String name; private CourseStatus status; private UUID world; private Region region; private Spot start;
        private List<BlockPos> goals; private int timeLimit; private List<ItemRef> loadout; private List<Trigger> triggers;
        private boolean failLava,failOutside; private int rev; private List<RoutePoint> route; private List<BlockPos> placeable;
        private Builder(Course c) {
            base=c; name=c.name; status=c.status; world=c.world; region=c.region; start=c.start; goals=new ArrayList<>(c.goals);
            timeLimit=c.timeLimitSeconds; loadout=new ArrayList<>(c.loadout); triggers=new ArrayList<>(c.triggers);
            failLava=c.failLava; failOutside=c.failOutside; rev=c.rev; route=new ArrayList<>(c.route); placeable=new ArrayList<>(c.placeable);
        }
        public Builder name(String v) { name=v; return this; }
        public Builder status(CourseStatus v) { status=v; return this; }
        public Builder start(Spot v) { start=v; return this; }
        public Builder timeLimit(int seconds) { timeLimit=Math.clamp(seconds,0,MAX_TIME_LIMIT); return this; }
        public Builder failLava(boolean v) { failLava=v; return this; }
        public Builder failOutside(boolean v) { failOutside=v; return this; }
        public Builder rev(int v) { rev=v; return this; }
        /** Moving the region to another world drops everything positioned in the old world. */
        public Builder region(UUID regionWorld,Region v) {
            if(!regionWorld.equals(world)) { start=null; goals.clear(); triggers.clear(); route.clear(); placeable.clear(); }
            world=regionWorld; region=v; return this;
        }
        /** Adds the goal block, or removes it when already registered. */
        public Builder toggleGoal(BlockPos goal) { if(!goals.remove(goal)) goals.add(goal); return this; }
        public Builder loadout(int slot,ItemRef item) { loadout.set(slot,item); return this; }
        /** Adds or replaces the trigger at its position. */
        public Builder trigger(Trigger trigger) {
            triggers.removeIf(t -> t.pos().equals(trigger.pos())); triggers.add(trigger); return this;
        }
        public Builder removeTrigger(BlockPos pos) { triggers.removeIf(t -> t.pos().equals(pos)); return this; }
        public Builder addRoutePoint(RoutePoint point) { route.add(point); return this; }
        public Builder removeLastRoutePoint() { if(!route.isEmpty()) route.removeLast(); return this; }
        public Builder clearRoute() { route.clear(); return this; }
        /** Adds the cell, or removes it when already registered. */
        public Builder togglePlaceable(BlockPos cell) { if(!placeable.remove(cell)) placeable.add(cell); return this; }
        public Builder clearPlaceable() { placeable.clear(); return this; }
        public Course build() {
            return new Course(base.id,name,status,base.author,world,region,start,goals,timeLimit,loadout,triggers,failLava,failOutside,rev,route,placeable);
        }
    }
    public Course withName(String value) { return edit().name(value).build(); }
    public Course withStatus(CourseStatus value) { return edit().status(value).build(); }
    public Course withRegion(UUID regionWorld,Region value) { return edit().region(regionWorld,value).build(); }
    public Course withStart(Spot value) { return edit().start(value).build(); }
    public Course toggleGoal(BlockPos goal) { return edit().toggleGoal(goal).build(); }
    public Course withTimeLimit(int seconds) { return edit().timeLimit(seconds).build(); }

    /** Same course to play: everything except name, status and the revision itself. */
    public boolean sameGameplay(Course other) {
        return Objects.equals(world,other.world) && Objects.equals(region,other.region) && Objects.equals(start,other.start)
                && goals.equals(other.goals) && timeLimitSeconds==other.timeLimitSeconds && loadout.equals(other.loadout)
                && unnamed(triggers).equals(unnamed(other.triggers)) && failLava.equals(other.failLava) && failOutside.equals(other.failOutside)
                && route.equals(other.route) && placeable.equals(other.placeable);
    }
    /** Trigger names are labels for builders only. */
    private static List<Trigger> unnamed(List<Trigger> triggers) { return triggers.stream().map(t -> t.withName(null)).toList(); }
    public boolean contains(Spot spot) { return region!=null && region.contains(spot.x(),spot.y(),spot.z()); }
    private boolean contains(BlockPos pos) { return region!=null && region.contains(pos.x(),pos.y(),pos.z()); }
    public boolean isGoal(int x,int y,int z) { return goals.contains(new BlockPos(x,y,z)); }
    /** True when blocks may be placed in this cell (always, without registered cells). */
    public boolean canPlace(int x,int y,int z) { return placeable.isEmpty() || placeable.contains(new BlockPos(x,y,z)); }
    public Trigger trigger(BlockPos pos) { return triggers.stream().filter(t -> t.pos().equals(pos)).findFirst().orElse(null); }
    /** What still has to be set before the course can be played, as message keys. */
    public List<String> problems() {
        List<String> problems=new ArrayList<>();
        if(region==null) problems.add("problem.no-region");
        if(start==null) problems.add("problem.no-start");
        else if(region!=null && !contains(start)) problems.add("problem.start-outside");
        if(goals.isEmpty()) problems.add("problem.no-goal");
        else if(region!=null && goals.stream().anyMatch(g -> !contains(g))) problems.add("problem.goal-outside");
        if(region!=null && triggers.stream().anyMatch(t -> !contains(t.pos()))) problems.add("problem.trigger-outside");
        if(region!=null && placeable.stream().anyMatch(p -> !contains(p))) problems.add("problem.placeable-outside");
        if(loadout.getFirst()==null) problems.add("problem.no-slot1");
        return problems;
    }
}
