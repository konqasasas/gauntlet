package konqasasas.gauntlet.course;

import java.util.*;

/**
 * An era (時代): courses joined one after another in one box, played in one go.
 * Clearing a course and walking onto the next one's start moves on to it in the
 * same lane. The era owns the lanes: its whole box is copied, and a player keeps
 * one lane for as long as they stay in the era. Records stay per course.
 *
 * courses is the order shown when choosing where to start. lanes are the offsets
 * of lanes 2, 3, ... (as for a course on its own).
 */
public record Era(String id,String name,UUID world,Region region,List<String> courses,List<BlockPos> lanes) {
    public Era {
        if(!CourseStore.validId(id) || name==null || name.isBlank() || name.length()>Course.MAX_NAME_LENGTH || world==null)
            throw new IllegalArgumentException("Invalid era "+id);
        courses=courses==null ? List.of() : List.copyOf(new LinkedHashSet<>(courses));
        lanes=lanes==null ? List.of() : List.copyOf(lanes);
        if(lanes.size()+1>Course.MAX_LANES || lanes.contains(BlockPos.ZERO) || new HashSet<>(lanes).size()!=lanes.size())
            throw new IllegalArgumentException("Invalid lanes");
    }
    public static Era create(String id,UUID world,Region region) { return new Era(id,id,world,region,null,null); }
    public Era withName(String v) { return new Era(id,v,world,region,courses,lanes); }
    /** Moving to another world drops the lanes (their copies are in the old world). */
    public Era withRegion(UUID w,Region v) { return new Era(id,name,w,v,courses,w.equals(world) ? lanes : null); }
    public Era withCourses(List<String> v) { return new Era(id,name,world,region,v,lanes); }
    public Era withLanes(List<BlockPos> v) { return new Era(id,name,world,region,courses,v); }
    public Space space() { return new Space(Space.ERA+id,name,world,region,lanes); }
    /** The era's first course, which anyone may start from. */
    public boolean first(String course) { return !courses.isEmpty() && courses.getFirst().equals(course); }
}
