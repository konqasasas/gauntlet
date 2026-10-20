package konqasasas.gauntlet.course;

import java.util.*;

/**
 * What lanes are made of: a box (lane 1) and the offsets of its copies (lanes 2, 3, ...).
 * An era is one; a course outside every era is one by itself. key tells them apart
 * ("era:<id>" or "course:<id>") and names the space in lane seats and pending syncs.
 */
public record Space(String key,String name,UUID world,Region region,List<BlockPos> lanes) {
    public static final String ERA="era:",COURSE="course:";
    public Space { lanes=lanes==null ? List.of() : List.copyOf(lanes); }
    /** Lane 1 included. */
    public int laneCount() { return lanes.size()+1; }
    /** Offset of the lane (0 = lane 1, the original). */
    public BlockPos laneOffset(int lane) { return lane==0 ? BlockPos.ZERO : lanes.get(lane-1); }
    public Region laneRegion(int lane) { return region==null ? null : region.moved(laneOffset(lane)); }
    /** The lane whose box holds the block, or -1. */
    public int laneAt(int x,int y,int z) {
        if(region==null) return -1;
        for(int lane=0;lane<laneCount();lane++) if(laneRegion(lane).contains(x,y,z)) return lane;
        return -1;
    }
    /** True when two lanes (lane 1 included) share a block. */
    public boolean lanesOverlap() {
        if(region==null) return false;
        for(int a=0;a<laneCount();a++) for(int b=a+1;b<laneCount();b++) if(laneRegion(a).overlaps(laneRegion(b))) return true;
        return false;
    }
    public boolean isEra() { return key.startsWith(ERA); }
}
