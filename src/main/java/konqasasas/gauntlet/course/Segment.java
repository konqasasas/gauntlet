package konqasasas.gauntlet.course;

import java.util.*;

/**
 * One module placed in a generated course: where it is (box), its time (ticks), and
 * the items it hands out (loadout with counts, as Module). module is the id of the
 * course it was copied from, moduleRev that course's revision then (times through the
 * module are kept per revision; 0 in files written before it existed).
 */
public record Segment(String module,Region box,int ticks,List<ItemRef> loadout,List<Integer> counts,Integer moduleRev) {
    public Segment {
        if(module==null || box==null || ticks<1) throw new IllegalArgumentException("Invalid segment");
        List<ItemRef> slots=new ArrayList<>(loadout==null ? List.of() : loadout);
        while(slots.size()<Course.COURSE_SLOTS) slots.add(null);
        loadout=Collections.unmodifiableList(slots.subList(0,Course.COURSE_SLOTS));
        counts=new Module(1,1,counts,false).counts();
        moduleRev=moduleRev==null ? 0 : moduleRev;
    }
    /**
     * The time limit of a generated course: its modules' times added up plus buffer percent of that
     * (rounded up), then startTicks (from the course's start to the first module, without a buffer).
     */
    public static int limit(List<Segment> segments,int startTicks,double bufferPercent) {
        long total=segments.stream().mapToLong(Segment::ticks).sum();
        return (int)Math.min(Integer.MAX_VALUE,(long)Math.ceil(total*(1+bufferPercent/100)-1e-9)+Math.max(0,startTicks));
    }
    public Segment moved(BlockPos d) { return new Segment(module,box.moved(d),ticks,loadout,counts,moduleRev); }
}
