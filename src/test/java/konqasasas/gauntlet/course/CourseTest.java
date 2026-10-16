package konqasasas.gauntlet.course;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CourseTest {
    private final UUID author=UUID.randomUUID(),world=UUID.randomUUID();

    @Test void regionNormalizesCornersAndContainsWholeBlocks() {
        Region r=Region.of(5,70,-3,-2,64,4);
        assertEquals(new Region(-2,64,-3,5,70,4),r);
        assertEquals(8,r.sizeX()); assertEquals(7,r.sizeY()); assertEquals(8,r.sizeZ());
        assertTrue(r.contains(5.99,70.99,4.99)); assertTrue(r.contains(-2,64,-3));
        assertFalse(r.contains(6,70,4)); assertFalse(r.contains(-2.01,64,-3));
    }
    @Test void problemsListWhatIsMissing() {
        Course course=Course.create("c",author,world);
        assertEquals(List.of("範囲が未設定","スタート地点が未設定","ゴールが未設定"),course.problems());
        course=course.withRegion(world,Region.of(0,0,0,9,9,9)).toggleGoal(new BlockPos(5,5,5));
        assertEquals(List.of("スタート地点が未設定"),course.problems());
        assertEquals(List.of("スタート地点が範囲の外"),course.withStart(new Spot(20,1,1,0,0)).problems());
        course=course.withStart(new Spot(1,1,1,0,0));
        assertEquals(List.of(),course.problems());
        assertEquals(List.of("範囲の外にゴールがある"),course.toggleGoal(new BlockPos(10,0,0)).problems());
    }
    @Test void movingRegionToAnotherWorldDropsStart() {
        Course course=Course.create("c",author,world).withRegion(world,Region.of(0,0,0,9,9,9)).withStart(new Spot(1,1,1,0,0));
        assertNotNull(course.withRegion(world,Region.of(0,0,0,5,5,5)).start());
        UUID other=UUID.randomUUID(); Course moved=course.withRegion(other,Region.of(0,0,0,5,5,5));
        assertEquals(other,moved.world()); assertNull(moved.start());
        assertEquals(List.of(),course.toggleGoal(new BlockPos(1,0,1)).withRegion(other,Region.of(0,0,0,5,5,5)).goals());
    }
    @Test void goalsToggleAndOldFilesWithoutGoalsLoad() {
        Course course=Course.create("c",author,world).toggleGoal(new BlockPos(1,2,3)).toggleGoal(new BlockPos(4,5,6));
        assertTrue(course.isGoal(1,2,3)); assertTrue(course.isGoal(4,5,6)); assertFalse(course.isGoal(1,2,4));
        assertEquals(List.of(new BlockPos(4,5,6)),course.toggleGoal(new BlockPos(1,2,3)).goals());
        assertEquals(List.of(),new Course("c","c",CourseStatus.DRAFT,author,world,null,null,null,30,null,null,null,null).goals());
    }
    @Test void timeLimitIsClampedAndStatusCycles() {
        Course course=Course.create("c",author,world);
        assertEquals(30,course.timeLimitSeconds());
        assertEquals(0,course.withTimeLimit(-5).timeLimitSeconds());
        assertEquals(Course.MAX_TIME_LIMIT,course.withTimeLimit(9999).timeLimitSeconds());
        assertEquals(CourseStatus.DRAFT,course.status().next().next().next());
    }
    @Test void invalidValuesAreRejected() {
        assertThrows(IllegalArgumentException.class,() -> Course.create("Bad ID",author,world));
        assertThrows(IllegalArgumentException.class,() -> Course.create("c",author,world).withName(" "));
        assertThrows(IllegalArgumentException.class,() -> Course.create("c",author,world).withName("x".repeat(33)));
        assertThrows(IllegalArgumentException.class,() -> new Spot(Double.NaN,0,0,0,0));
        assertThrows(IllegalArgumentException.class,() -> new Region(1,0,0,0,0,0));
    }
}
