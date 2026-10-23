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
    /** A rule of a profile: between min and max places have a difficulty from..to (inclusive). */
    public record Band(int from,int to,int min,int max) {
        public Band {
            if(from<Module.MIN_DIFFICULTY || to>Module.MAX_DIFFICULTY || from>to || min<0 || max<min) throw new IllegalArgumentException("Invalid band");
        }
        boolean holds(int difficulty) { return difficulty>=from && difficulty<=to; }
        /** "4-5:1-2" */
        public String text() { return from+"-"+to+":"+min+"-"+max; }
        public static Band parse(String text) {
            String[] p=text.strip().split("[-:]");
            if(p.length!=4) throw new IllegalArgumentException("Invalid band "+text);
            return new Band(Integer.parseInt(p[0].strip()),Integer.parseInt(p[1].strip()),Integer.parseInt(p[2].strip()),Integer.parseInt(p[3].strip()));
        }
    }
    /** The bands of a profile cannot all hold; which one could not be met. */
    public static final class BandConflict extends IllegalStateException {
        public final Band band;
        BandConflict(Band band) { super("Band cannot be met: "+band.text()); this.band=band; }
    }
    /**
     * modules: how many. weights: of difficulties 1-5 (any scale). bumpiness: how far a place may move
     * from easy-to-hard order. bands: how many places a difficulty range must have.
     */
    public record Profile(int modules,List<Double> weights,double bumpiness,List<Band> bands) {
        public static final int LEVELS=Module.MAX_DIFFICULTY-Module.MIN_DIFFICULTY+1;
        public Profile {
            weights=List.copyOf(weights);
            bands=bands==null ? List.of() : List.copyOf(bands);
            if(modules<1 || weights.size()!=LEVELS || weights.stream().anyMatch(w -> w==null || w<0 || !Double.isFinite(w))
                    || weights.stream().mapToDouble(Double::doubleValue).sum()<=0 || bumpiness<0 || !Double.isFinite(bumpiness))
                throw new IllegalArgumentException("Invalid profile");
        }
        public Profile(int modules,List<Double> weights,double bumpiness) { this(modules,weights,bumpiness,null); }
        /** Only this difficulty. */
        public static Profile only(int modules,int difficulty,double bumpiness) {
            List<Double> w=new ArrayList<>(Collections.nCopies(LEVELS,0.0)); w.set(difficulty-Module.MIN_DIFFICULTY,1.0);
            return new Profile(modules,w,bumpiness);
        }
        /**
         * The difficulty of each place: drawn from the weights, changed until every band holds
         * (a place outside a band that needs more is redrawn inside it, and the other way round,
         * by the weights), then put easy to hard and shaken by the bumpiness.
         * Throws BandConflict when the bands cannot all hold.
         */
        List<Integer> targets(Random random) {
            List<Integer> drawn=new ArrayList<>();
            for(int i=0;i<modules;i++) drawn.add(draw(random,d -> true));
            for(int round=0;round<modules*bands.size()*4+8 && !bands.isEmpty();round++) {
                boolean changed=false;
                for(Band band:bands) {
                    long inside=drawn.stream().filter(band::holds).count();
                    if(inside<band.min()) {
                        List<Integer> outside=new ArrayList<>();
                        for(int i=0;i<drawn.size();i++) if(!band.holds(drawn.get(i))) outside.add(i);
                        if(outside.isEmpty()) throw new BandConflict(band);
                        drawn.set(pick(outside,drawn,random),draw(random,band::holds)); changed=true;
                    } else if(inside>band.max()) {
                        List<Integer> in=new ArrayList<>();
                        for(int i=0;i<drawn.size();i++) if(band.holds(drawn.get(i))) in.add(i);
                        if(band.from()==Module.MIN_DIFFICULTY && band.to()==Module.MAX_DIFFICULTY) throw new BandConflict(band);
                        drawn.set(pick(in,drawn,random),draw(random,d -> !band.holds(d))); changed=true;
                    }
                }
                if(!changed) break;
            }
            for(Band band:bands) {
                long inside=drawn.stream().filter(band::holds).count();
                if(inside<band.min() || inside>band.max()) throw new BandConflict(band);
            }
            Collections.sort(drawn);
            record Slot(int difficulty,double key) {}
            List<Slot> slots=new ArrayList<>();
            for(int i=0;i<drawn.size();i++) slots.add(new Slot(drawn.get(i),i+random.nextGaussian()*bumpiness));
            slots.sort(Comparator.comparingDouble(Slot::key));
            return slots.stream().map(Slot::difficulty).toList();
        }
        /** One of these places, preferring one whose change leaves every band at or above its min. */
        private int pick(List<Integer> places,List<Integer> drawn,Random random) {
            List<Integer> free=new ArrayList<>();
            for(int i:places) {
                int d=drawn.get(i);
                if(bands.stream().noneMatch(b -> b.holds(d) && drawn.stream().filter(b::holds).count()<=b.min())) free.add(i);
            }
            List<Integer> from=free.isEmpty() ? places : free;
            return from.get(random.nextInt(from.size()));
        }
        /** A difficulty allowed by the filter, by the weights (evenly when the allowed ones all weigh 0). */
        private int draw(Random random,java.util.function.IntPredicate allowed) {
            double total=0;
            for(int l=0;l<LEVELS;l++) if(allowed.test(l+Module.MIN_DIFFICULTY)) total+=weights.get(l);
            if(total<=0) {
                List<Integer> any=new ArrayList<>();
                for(int l=0;l<LEVELS;l++) if(allowed.test(l+Module.MIN_DIFFICULTY)) any.add(l+Module.MIN_DIFFICULTY);
                return any.get(random.nextInt(any.size()));
            }
            double r=random.nextDouble()*total;
            for(int l=0;l<LEVELS;l++) {
                if(!allowed.test(l+Module.MIN_DIFFICULTY)) continue;
                if(r<weights.get(l)) return l+Module.MIN_DIFFICULTY;
                r-=weights.get(l);
            }
            for(int l=LEVELS-1;l>=0;l--) if(allowed.test(l+Module.MIN_DIFFICULTY)) return l+Module.MIN_DIFFICULTY;
            throw new IllegalStateException("No difficulty allowed");
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
