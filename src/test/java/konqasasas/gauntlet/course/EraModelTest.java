package konqasasas.gauntlet.course;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EraModelTest {
    @TempDir Path dir;
    private final UUID author=UUID.randomUUID(),world=UUID.randomUUID();

    private Course course(String id,int x) {
        return Course.create(id,author,world).withRegion(world,Region.of(x,0,0,x+9,9,9)).withStart(new Spot(x+1,1,1,0,0)).toggleGoal(new BlockPos(x+8,0,8));
    }

    @Test void eraOwnsTheLanesOfItsCourses() throws Exception {
        CourseStore courses=new CourseStore(dir.resolve("courses"));
        courses.create(course("a",0)); courses.create(course("b",10)); courses.create(course("solo",100).edit().lanes(List.of(new BlockPos(0,0,50))).build());
        EraStore eras=new EraStore(dir.resolve("eras.json"));
        Era era=Era.create("e1",world,Region.of(0,0,0,19,9,9)).withCourses(List.of("a","b")).withLanes(List.of(new BlockPos(0,0,30)));
        eras.save(era);
        Spaces spaces=new Spaces(courses,eras);
        Space space=spaces.of(courses.get("b"));
        assertEquals("era:e1",space.key()); assertEquals(2,space.laneCount());
        assertEquals(space,spaces.of(courses.get("a")),"One space for the whole era");
        assertEquals(new Spot(11,1,31,0,0),spaces.inLane(courses.get("b"),1).start(),"Moved by the era's offset");
        assertSame(courses.get("a"),spaces.inLane(courses.get("a"),0));
        assertEquals("course:solo",spaces.of(courses.get("solo")).key(),"Outside the eras a course has its own lanes");
        assertEquals(new Spot(101,1,51,0,0),spaces.inLane(courses.get("solo"),1).start());
        assertEquals(Set.of("era:e1","course:solo"),new HashSet<>(spaces.all().stream().map(Space::key).toList()));
        assertNull(spaces.get("course:a"),"A course in an era has no space of its own");
        assertEquals(space,spaces.get("era:e1"));
        assertEquals(1,space.laneAt(15,5,35)); assertEquals(0,space.laneAt(15,5,5)); assertEquals(-1,space.laneAt(15,5,20));
        assertTrue(era.first("a")); assertFalse(era.first("b"));
    }
    @Test void erasPersistAndACourseBelongsToOneEra() throws Exception {
        EraStore eras=new EraStore(dir.resolve("eras.json"));
        eras.save(Era.create("e1",world,Region.of(0,0,0,9,9,9)).withName("時代1").withCourses(List.of("a","b")));
        eras.save(Era.create("e2",world,null).withCourses(List.of("b","c")));
        assertEquals(List.of("a"),eras.get("e1").courses(),"b moved to e2");
        assertEquals("e2",eras.of("b").id()); assertNull(eras.of("x"));
        EraStore loaded=new EraStore(dir.resolve("eras.json"));
        assertEquals(eras.all(),loaded.all());
        assertEquals("時代1",loaded.get("e1").name());
        loaded.delete("e1");
        assertNull(new EraStore(dir.resolve("eras.json")).get("e1"));
        assertThrows(IllegalArgumentException.class,() -> Era.create("Bad ID",world,null));
        assertEquals(List.of("a"),Era.create("e",world,null).withCourses(List.of("a","a")).courses());
        Era laned=Era.create("e",world,Region.of(0,0,0,9,9,9)).withLanes(List.of(new BlockPos(5,0,0)));
        assertTrue(laned.space().lanesOverlap());
        assertEquals(List.of(),laned.withRegion(UUID.randomUUID(),Region.of(0,0,0,1,1,1)).lanes(),"Another world drops the lanes");
        assertEquals(laned.lanes(),laned.withRegion(world,Region.of(0,0,0,1,1,1)).lanes());
    }
    @Test void regionContainsAnotherRegion() {
        Region big=Region.of(0,0,0,19,9,9);
        assertTrue(big.contains(Region.of(10,0,0,19,9,9)));
        assertFalse(big.contains(Region.of(10,0,0,20,9,9)));
        assertFalse(big.contains((Region)null));
    }
}
