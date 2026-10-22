package konqasasas.gauntlet.course;

import java.util.*;

/**
 * Plans a generated course: which modules, in which order, and where each goes.
 *
 * Difficulty goes from the profile's first value to its last in a straight line;
 * at each step the modules closest to that difficulty are tried in a random order
 * (fixed by the seed), never the same module twice in a row, and never more than
 * maxHardStreak hard modules (difficulty hardFrom or more) one after another.
 *
 * Modules are not turned: everything goes along +Z. Each module is moved so that
 * its start block lands on the previous module's goal block (no joint), or after
 * a flat joint of jointLength blocks. A module that would leave the world's height
 * or whose blocks would hit blocks already placed is skipped for the next candidate;
 * when none fits, the plan stops and says where.
 */
public final class Generator {
    /** A module: start = the block under its start spot, exit = its goal block, solid = its non-air blocks. */
    public record Piece(String id,int difficulty,BlockPos start,BlockPos exit,Region region,Set<BlockPos> solid) {}
    /** modules: how many; difficulty goes from fromDifficulty to toDifficulty. */
    public record Profile(int modules,int fromDifficulty,int toDifficulty) {
        public Profile { if(modules<1 || fromDifficulty<Module.MIN_DIFFICULTY || toDifficulty>Module.MAX_DIFFICULTY || toDifficulty<Module.MIN_DIFFICULTY || fromDifficulty>Module.MAX_DIFFICULTY) throw new IllegalArgumentException("Invalid profile"); }
        int target(int index) { return modules==1 ? fromDifficulty : Math.round(fromDifficulty+(toDifficulty-fromDifficulty)*index/(float)(modules-1)); }
    }
    /** jointLength 0 = no joint. minY..maxY: the world's height (inclusive). */
    public record Settings(int hardFrom,int maxHardStreak,int jointLength,int minY,int maxY) {}
    public record Placed(Piece piece,BlockPos offset) {
        public BlockPos start() { return piece.start().plus(offset); }
        public BlockPos exit() { return piece.exit().plus(offset); }
        public Region region() { return piece.region().moved(offset); }
    }
    /** failure: null when every module was placed; else why it stopped, at index failedAt. */
    public record Plan(List<Placed> placed,List<BlockPos> joints,String failure,int failedAt,int target) {
        public boolean ok() { return failure==null; }
    }

    public static Plan plan(List<Piece> pieces,Profile profile,Settings settings,BlockPos origin,long seed) {
        Random random=new Random(seed);
        List<Placed> placed=new ArrayList<>();
        List<BlockPos> joints=new ArrayList<>();
        Set<BlockPos> occupied=new HashSet<>();
        for(int i=0;i<profile.modules();i++) {
            int target=profile.target(i);
            Placed last=placed.isEmpty() ? null : placed.getLast();
            BlockPos at=last==null ? origin : last.exit().plus(new BlockPos(0,0,settings.jointLength()>0 ? settings.jointLength()+1 : 0));
            List<BlockPos> joint=new ArrayList<>();
            if(last!=null && settings.jointLength()>0) for(int k=0;k<=settings.jointLength();k++) joint.add(last.exit().plus(new BlockPos(0,0,k)));
            boolean streak=placed.size()>=settings.maxHardStreak()
                    && placed.subList(placed.size()-settings.maxHardStreak(),placed.size()).stream().allMatch(p -> p.piece().difficulty()>=settings.hardFrom());
            List<Piece> candidates=new ArrayList<>(pieces);
            Collections.shuffle(candidates,random);
            candidates.sort(Comparator.comparingInt(p -> Math.abs(p.difficulty()-target)));
            String why=pieces.isEmpty() ? "no-modules" : "no-candidate";
            Placed chosen=null;
            for(Piece piece:candidates) {
                if(last!=null && piece.id().equals(last.piece().id())) continue;
                if(streak && piece.difficulty()>=settings.hardFrom()) continue;
                BlockPos offset=at.minus(piece.start());
                Region r=piece.region().moved(offset);
                if(r.minY()<settings.minY() || r.maxY()>settings.maxY()) { why="height"; continue; }
                boolean hit=false;
                for(BlockPos c:piece.solid()) {
                    BlockPos moved=c.plus(offset);
                    // The start block may sit where the previous goal block was (no joint).
                    if(moved.equals(at) && settings.jointLength()==0) continue;
                    if(occupied.contains(moved) || joint.contains(moved)) { hit=true; break; }
                }
                if(hit) { why="overlap"; continue; }
                chosen=new Placed(piece,offset); break;
            }
            if(chosen==null) return new Plan(placed,joints,why,i,target);
            joints.addAll(joint);
            for(BlockPos c:chosen.piece().solid()) occupied.add(c.plus(chosen.offset()));
            occupied.addAll(joint);
            placed.add(chosen);
        }
        return new Plan(placed,joints,null,-1,0);
    }
}
