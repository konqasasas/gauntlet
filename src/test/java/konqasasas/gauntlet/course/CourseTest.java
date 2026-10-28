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
        assertEquals(List.of("problem.no-region","problem.no-start","problem.no-goal"),course.problems());
        course=course.withRegion(world,Region.of(0,0,0,9,9,9)).toggleGoal(new BlockPos(5,5,5));
        assertEquals(List.of("problem.no-start"),course.problems());
        assertEquals(List.of("problem.start-outside"),course.withStart(new Spot(20,1,1,0,0)).problems());
        course=course.withStart(new Spot(1,1,1,0,0));
        assertEquals(List.of(),course.problems());
        assertEquals(List.of("problem.goal-outside"),course.toggleGoal(new BlockPos(10,0,0)).problems());
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
        assertEquals(List.of(),new Course("c","c",CourseStatus.DRAFT,author,world,null,null,null,30,null,null,null,null,null,null,null,null,null,null,null).goals());
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
    @Test void onlyGameplayEditsChangeTheGame() {
        Course base=Course.create("c",author,world).withRegion(world,Region.of(0,0,0,9,9,9));
        assertTrue(base.sameGameplay(base.withName("Lava Stairs")));
        assertTrue(base.sameGameplay(base.withStatus(CourseStatus.DONE)));
        assertTrue(base.sameGameplay(base.edit().rev(7).build()));
        assertFalse(base.sameGameplay(base.withTimeLimit(31)));
        assertFalse(base.sameGameplay(base.withStart(new Spot(1,1,1,0,0))));
        assertFalse(base.sameGameplay(base.edit().loadout(2,ItemRef.scaffold()).build()));
        assertFalse(base.sameGameplay(base.edit().addRoutePoint(new RoutePoint(1,2,3)).build()));
        assertFalse(base.sameGameplay(base.edit().togglePlaceable(new BlockPos(1,2,3)).build()));
        assertEquals(0,base.rev());
    }
    @Test void placeableCellsRestrictPlacementOnlyWhenRegistered() {
        Course c=Course.create("c",author,world).withRegion(world,Region.of(0,0,0,9,9,9));
        assertTrue(c.canPlace(50,50,50),"No cells: anywhere");
        Course one=c.edit().togglePlaceable(new BlockPos(1,1,1)).build();
        assertTrue(one.canPlace(1,1,1)); assertFalse(one.canPlace(1,2,1));
        assertEquals(List.of(),one.edit().togglePlaceable(new BlockPos(1,1,1)).build().placeable(),"Placing the marker again removes the cell");
        assertEquals(List.of("problem.placeable-outside"),one.edit().togglePlaceable(new BlockPos(20,0,0)).build().problems().stream()
                .filter(p -> p.startsWith("problem.placeable")).toList());
    }
    @Test void generatedLimitIsTheModuleTimesPlusTheBuffer() {
        Region box=Region.of(0,0,0,1,1,1);
        List<Segment> segments=List.of(new Segment("a",box,200,null,null,0),new Segment("b",box,101,null,null,0));
        assertEquals(301,Segment.limit(segments,0));
        assertEquals(332,Segment.limit(segments,10),"301 x 1.1 = 331.1, rounded up");
        assertEquals(330,Segment.limit(List.of(new Segment("a",box,300,null,null,0)),10),"Exact values are not rounded up");
    }
    @Test void moduleTimeAndDifficultyDoNotChangeHowItPlays() {
        Course plain=Course.create("m",author,world);
        Course module=plain.edit().module(Module.standard()).build();
        assertTrue(module.sameGameplay(module.edit().module(module.module().withTicks(345).withDifficulty(4)).build()));
        assertFalse(module.sameGameplay(module.edit().module(module.module().withCount(0,5)).build()));
        assertFalse(plain.sameGameplay(module),"Turning it into a module does");
    }
    @Test void oldFourSlotLoadouts() {
        Course four=new Course("c","c",CourseStatus.DRAFT,author,world,null,null,null,30,
                Arrays.asList(ItemRef.scaffold(),null,new ItemRef("t",0),new ItemRef("lost",0)),null,null,null,null,null,null,null,null,null,null);
        assertEquals(Arrays.asList(ItemRef.scaffold(),null,new ItemRef("t",0)),four.loadout(),"The old fourth slot is dropped");
        assertEquals(0,four.rev()); assertEquals(List.of(),four.route());
    }
}
