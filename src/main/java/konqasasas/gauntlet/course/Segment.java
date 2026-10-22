package konqasasas.gauntlet.course;

import java.util.*;

/**
 * One module placed in a generated course: where it is (box), the time it adds
 * when entered, and the items it hands out (loadout with counts, as Module).
 * module is the id of the course it was copied from.
 */
public record Segment(String module,Region box,int seconds,List<ItemRef> loadout,List<Integer> counts) {
    public Segment {
        if(module==null || box==null || seconds<1) throw new IllegalArgumentException("Invalid segment");
        List<ItemRef> slots=new ArrayList<>(loadout==null ? List.of() : loadout);
        while(slots.size()<Course.COURSE_SLOTS) slots.add(null);
        loadout=Collections.unmodifiableList(slots.subList(0,Course.COURSE_SLOTS));
        counts=new Module(1,1,counts).counts();
    }
    public Segment moved(BlockPos d) { return new Segment(module,box.moved(d),seconds,loadout,counts); }
}
