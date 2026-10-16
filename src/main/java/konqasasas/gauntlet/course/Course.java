package konqasasas.gauntlet.course;

import java.util.*;

/**
 * One course definition. Region and start stay null until the builder sets them.
 * loadout[i] is hotbar slot i+1 (null = empty). Fields added later are null in
 * older files and get their defaults here.
 */
public record Course(String id,String name,CourseStatus status,UUID author,UUID world,Region region,Spot start,
                     List<BlockPos> goals,int timeLimitSeconds,List<ItemRef> loadout,List<Trigger> triggers,
                     Boolean failLava,Boolean failOutside) {
    public static final int DEFAULT_TIME_LIMIT=30,MAX_TIME_LIMIT=600,MAX_NAME_LENGTH=32,COURSE_SLOTS=4;
    public Course {
        if(!CourseStore.validId(id) || name==null || name.isBlank() || name.length()>MAX_NAME_LENGTH
                || status==null || author==null || world==null || timeLimitSeconds<0 || timeLimitSeconds>MAX_TIME_LIMIT)
            throw new IllegalArgumentException("Invalid course "+id);
        goals=goals==null ? List.of() : List.copyOf(new LinkedHashSet<>(goals));
        if(loadout==null) { loadout=new ArrayList<>(Collections.nCopies(COURSE_SLOTS,null)); loadout.set(0,ItemRef.scaffold()); }
        if(loadout.size()!=COURSE_SLOTS) throw new IllegalArgumentException("Loadout must have "+COURSE_SLOTS+" slots");
        loadout=Collections.unmodifiableList(new ArrayList<>(loadout));
        triggers=triggers==null ? List.of() : List.copyOf(triggers);
        if(triggers.stream().map(Trigger::pos).distinct().count()!=triggers.size()) throw new IllegalArgumentException("Duplicate trigger");
        failLava=failLava==null || failLava;
        failOutside=failOutside==null || failOutside;
    }
    public static Course create(String id,UUID author,UUID world) {
        return new Course(id,id,CourseStatus.DRAFT,author,world,null,null,null,DEFAULT_TIME_LIMIT,null,null,null,null);
    }

    public Builder edit() { return new Builder(this); }
    public static final class Builder {
        private final Course base;
        private String name; private CourseStatus status; private UUID world; private Region region; private Spot start;
        private List<BlockPos> goals; private int timeLimit; private List<ItemRef> loadout; private List<Trigger> triggers;
        private boolean failLava,failOutside;
        private Builder(Course c) {
            base=c; name=c.name; status=c.status; world=c.world; region=c.region; start=c.start; goals=new ArrayList<>(c.goals);
            timeLimit=c.timeLimitSeconds; loadout=new ArrayList<>(c.loadout); triggers=new ArrayList<>(c.triggers);
            failLava=c.failLava; failOutside=c.failOutside;
        }
        public Builder name(String v) { name=v; return this; }
        public Builder status(CourseStatus v) { status=v; return this; }
        public Builder start(Spot v) { start=v; return this; }
        public Builder timeLimit(int seconds) { timeLimit=Math.clamp(seconds,0,MAX_TIME_LIMIT); return this; }
        public Builder failLava(boolean v) { failLava=v; return this; }
        public Builder failOutside(boolean v) { failOutside=v; return this; }
        /** Moving the region to another world drops everything positioned in the old world. */
        public Builder region(UUID regionWorld,Region v) {
            if(!regionWorld.equals(world)) { start=null; goals.clear(); triggers.clear(); }
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
        public Course build() {
            return new Course(base.id,name,status,base.author,world,region,start,goals,timeLimit,loadout,triggers,failLava,failOutside);
        }
    }
    public Course withName(String value) { return edit().name(value).build(); }
    public Course withStatus(CourseStatus value) { return edit().status(value).build(); }
    public Course withRegion(UUID regionWorld,Region value) { return edit().region(regionWorld,value).build(); }
    public Course withStart(Spot value) { return edit().start(value).build(); }
    public Course toggleGoal(BlockPos goal) { return edit().toggleGoal(goal).build(); }
    public Course withTimeLimit(int seconds) { return edit().timeLimit(seconds).build(); }

    public boolean contains(Spot spot) { return region!=null && region.contains(spot.x(),spot.y(),spot.z()); }
    private boolean contains(BlockPos pos) { return region!=null && region.contains(pos.x(),pos.y(),pos.z()); }
    public boolean isGoal(int x,int y,int z) { return goals.contains(new BlockPos(x,y,z)); }
    public Trigger trigger(BlockPos pos) { return triggers.stream().filter(t -> t.pos().equals(pos)).findFirst().orElse(null); }
    /** What still has to be set before the course can be played. */
    public List<String> problems() {
        List<String> problems=new ArrayList<>();
        if(region==null) problems.add("範囲が未設定");
        if(start==null) problems.add("スタート地点が未設定");
        else if(region!=null && !contains(start)) problems.add("スタート地点が範囲の外");
        if(goals.isEmpty()) problems.add("ゴールが未設定");
        else if(region!=null && goals.stream().anyMatch(g -> !contains(g))) problems.add("範囲の外にゴールがある");
        if(region!=null && triggers.stream().anyMatch(t -> !contains(t.pos()))) problems.add("範囲の外にトリガーがある");
        if(loadout.getFirst()==null) problems.add("初期装備の1番が空（1番でQが使えない）");
        return problems;
    }
}
