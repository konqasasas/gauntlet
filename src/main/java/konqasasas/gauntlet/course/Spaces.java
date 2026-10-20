package konqasasas.gauntlet.course;

import java.util.*;

/** Where the lanes of a course come from: its era, or the course itself when it is in none. */
public final class Spaces {
    private final CourseStore courses;
    private final EraStore eras;
    public Spaces(CourseStore courses,EraStore eras) { this.courses=courses; this.eras=eras; }
    public EraStore eras() { return eras; }
    public Era era(Course course) { return eras.of(course.id()); }
    public Space of(Course course) { Era era=era(course); return era==null ? course.space() : era.space(); }
    /** By key; null when the era or course is gone (or the course joined an era). */
    public Space get(String key) {
        if(key.startsWith(Space.ERA)) { Era era=eras.get(key.substring(Space.ERA.length())); return era==null ? null : era.space(); }
        Course course=courses.get(key.startsWith(Space.COURSE) ? key.substring(Space.COURSE.length()) : key);
        return course==null || eras.of(course.id())!=null ? null : course.space();
    }
    /** Every era and every course outside the eras. */
    public List<Space> all() {
        List<Space> all=new ArrayList<>();
        for(Era era:eras.all()) all.add(era.space());
        for(Course course:courses.all()) if(eras.of(course.id())==null) all.add(course.space());
        return all;
    }
    /** The course as played in that lane of its space. */
    public Course inLane(Course course,int lane) {
        BlockPos d=of(course).laneOffset(lane);
        return d.equals(BlockPos.ZERO) && course.lanes().isEmpty() ? course : course.moved(d);
    }
}
