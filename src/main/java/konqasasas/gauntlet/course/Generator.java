package konqasasas.gauntlet.course;

import java.util.*;

/**
 * Plans a generated course: which modules, in which order, and where each goes.
 *
 * Difficulty: the profile gives a distribution (weights of difficulties 1-5) and a
 * bumpiness. The course's difficulties are drawn from the distribution, then put in
 * order from easy to hard, with each place shaken by the bumpiness (0: strictly
 * easy to hard; more: more ups and downs), like a bumpy climb. At each place the
 * unused modules closest to that difficulty are tried in a random order. Everything
 * random comes from the seed. A module is used once per course (mirrored or not),
 * and no more than maxHardStreak hard modules (difficulty hardFrom or more) follow
 * one another.
 *
 * Modules are not turned: everything goes along +Z. Each one is placed as built or,
 * half the time (by the seed), with left and right swapped (mirrored in x around its
 * start), so one module gives two layouts. Each module is moved so that
 * its start block lands on the previous module's goal block (no joint), or after
 * a flat joint of jointLength blocks. A module that would leave the world's height
 * or whose blocks would hit blocks already placed is skipped for the next candidate;
 * when none fits, the plan stops and says where.
 */
public final class Generator {
    /** A module: start = the block under its start spot, exit = its goal block, solid = its non-air blocks. */
    public record Piece(String id,int difficulty,BlockPos start,BlockPos exit,Region region,Set<BlockPos> solid) {
        /** Left and right swapped around the start's column. */
        public Piece mirrored() {
            int a=start.x();
            Set<BlockPos> m=new HashSet<>(); for(BlockPos c:solid) m.add(c.mirrorX(a));
            return new Piece(id,difficulty,start,exit.mirrorX(a),region.mirrorX(a),m);
        }
    }
    /** modules: how many. weights: of difficulties 1-5 (any scale). bumpiness: how far a place may move from easy-to-hard order. */
    public record Profile(int modules,List<Double> weights,double bumpiness) {
        public static final int LEVELS=Module.MAX_DIFFICULTY-Module.MIN_DIFFICULTY+1;
        public Profile {
            weights=List.copyOf(weights);
            if(modules<1 || weights.size()!=LEVELS || weights.stream().anyMatch(w -> w==null || w<0 || !Double.isFinite(w))
                    || weights.stream().mapToDouble(Double::doubleValue).sum()<=0 || bumpiness<0 || !Double.isFinite(bumpiness))
                throw new IllegalArgumentException("Invalid profile");
        }
        /** Only this difficulty. */
        public static Profile only(int modules,int difficulty,double bumpiness) {
            List<Double> w=new ArrayList<>(Collections.nCopies(LEVELS,0.0)); w.set(difficulty-Module.MIN_DIFFICULTY,1.0);
            return new Profile(modules,w,bumpiness);
        }
        /** The difficulty of each place: drawn from the weights, easy to hard, shaken by the bumpiness. */
        List<Integer> targets(Random random) {
            double total=weights.stream().mapToDouble(Double::doubleValue).sum();
            List<Integer> drawn=new ArrayList<>();
            for(int i=0;i<modules;i++) {
                double r=random.nextDouble()*total; int level=0;
                while(level<LEVELS-1 && r>=weights.get(level)) { r-=weights.get(level); level++; }
                drawn.add(level+Module.MIN_DIFFICULTY);
            }
            Collections.sort(drawn);
            record Slot(int difficulty,double key) {}
            List<Slot> slots=new ArrayList<>();
            for(int i=0;i<drawn.size();i++) slots.add(new Slot(drawn.get(i),i+random.nextGaussian()*bumpiness));
            slots.sort(Comparator.comparingDouble(Slot::key));
            return slots.stream().map(Slot::difficulty).toList();
        }
    }
    /** jointLength 0 = no joint. minY..maxY: the world's height (inclusive). */
    public record Settings(int hardFrom,int maxHardStreak,int jointLength,int minY,int maxY) {}
    /** piece is as placed (mirrored when mirrored is true); its start column is the mirror's axis. */
    public record Placed(Piece piece,BlockPos offset,boolean mirrored) {
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
        Set<String> used=new HashSet<>();
        List<Integer> targets=profile.targets(random);
        for(int i=0;i<profile.modules();i++) {
            int target=targets.get(i);
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
            for(Piece built:candidates) {
                if(used.contains(built.id())) continue;
                boolean mirror=random.nextBoolean();
                Piece piece=mirror ? built.mirrored() : built;
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
                chosen=new Placed(piece,offset,mirror); break;
            }
            if(chosen==null) return new Plan(placed,joints,why,i,target);
            joints.addAll(joint);
            for(BlockPos c:chosen.piece().solid()) occupied.add(c.plus(chosen.offset()));
            occupied.addAll(joint);
            placed.add(chosen); used.add(chosen.piece().id());
        }
        return new Plan(placed,joints,null,-1,0);
    }
}
