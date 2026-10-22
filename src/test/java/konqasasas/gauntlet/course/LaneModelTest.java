package konqasasas.gauntlet.course;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LaneModelTest {
    @TempDir Path dir;
    private final UUID author=UUID.randomUUID(),world=UUID.randomUUID();
    private final BlockPos plate=new BlockPos(2,1,2);
    private final TriggerAction mob=TriggerAction.mob("minecraft:zombie",new Spot(3.5,1,3.5,90,10)),
            water=TriggerAction.water(List.of(new BlockPos(4,2,4),new BlockPos(5,2,4)),40),
            sound=TriggerAction.sound("minecraft:block.bell.use",1,1);

    private Course course() {
        return Course.create("c",author,world).withRegion(world,Region.of(0,0,0,9,9,9)).withStart(new Spot(1,1,1,45,0))
                .toggleGoal(new BlockPos(8,0,8)).edit().trigger(new Trigger(plate,List.of(mob,water,sound),"入口"))
                .togglePlaceable(new BlockPos(5,1,5)).addRoutePoint(new RoutePoint(1,2,1)).build();
    }

    @Test void actionsMoveWithTheirPositions() {
        BlockPos d=new BlockPos(100,0,-20);
        assertEquals(new Spot(103.5,1,-16.5,90,10),mob.moved(d).mobAt(),"The mob keeps its facing");
        assertEquals(List.of(new BlockPos(104,2,-16),new BlockPos(105,2,-16)),water.moved(d).waterCells());
        assertEquals(40,water.moved(d).waterTicks());
        assertSame(sound,sound.moved(d),"Actions without a position stay");
        assertSame(mob,mob.moved(BlockPos.ZERO));
        Trigger t=new Trigger(plate,List.of(mob,water),"n").moved(d);
        assertEquals(new BlockPos(102,1,-18),t.pos()); assertEquals("n",t.name());
        assertEquals(mob.moved(d),t.actions().getFirst());
    }
    @Test void aLaneIsTheWholeCourseMoved() {
        BlockPos d=new BlockPos(200,0,0);
        Course base=course().edit().lanes(List.of(d)).build();
        assertEquals(2,base.laneCount());
        assertSame(base.laneOffset(0),BlockPos.ZERO);
        Course lane2=base.inLane(1);
        assertEquals(Region.of(200,0,0,209,9,9),lane2.region());
        assertEquals(new Spot(201,1,1,45,0),lane2.start());
        assertTrue(lane2.isGoal(208,0,8)); assertFalse(lane2.isGoal(8,0,8));
        assertNotNull(lane2.trigger(new BlockPos(202,1,2))); assertNull(lane2.trigger(plate));
        assertEquals(mob.moved(d),lane2.trigger(new BlockPos(202,1,2)).actions().getFirst());
        assertTrue(lane2.canPlace(205,1,5)); assertFalse(lane2.canPlace(5,1,5));
        assertEquals(List.of(new RoutePoint(201,2,1)),lane2.route());
        assertEquals(List.of(),lane2.lanes(),"A lane has no lanes of its own");
        assertEquals(List.of(),lane2.problems());
        assertEquals(base.rev(),lane2.rev(),"Same revision: records are shared by the lanes");
        assertEquals(base.inLane(0).region(),base.region());
    }
    @Test void mirroringSwapsLeftAndRight() {
        Course m=course().mirrorX(1);
        assertEquals(Region.of(-7,0,0,2,9,9),m.region());
        assertEquals(new Spot(2,1,1,-45,0),m.start(),"The start (x 1) stays in its column, facing the other way");
        assertTrue(m.isGoal(-6,0,8));
        Trigger t=m.trigger(new BlockPos(0,1,2));
        assertNotNull(t);
        assertEquals(new Spot(-0.5,1,3.5,-90,10),t.actions().getFirst().mobAt());
        assertEquals(List.of(new BlockPos(-2,2,4),new BlockPos(-3,2,4)),t.actions().get(1).waterCells());
        assertTrue(m.canPlace(-3,1,5));
        assertEquals(List.of(new RoutePoint(2,2,1)),m.route());
        assertEquals(course(),m.mirrorX(1),"Twice is the original");
        assertTrue(NaturalOrder.compare("m1-2","m1-10")<0);
        assertTrue(NaturalOrder.compare("m2-1","m1-10")>0);
        assertTrue(NaturalOrder.compare("M1","m1")==0);
    }
    @Test void laneAtFindsTheCopy() {
        Course base=course().edit().lanes(List.of(new BlockPos(20,0,0),new BlockPos(40,0,0))).build();
        assertEquals(0,base.laneAt(0,0,0)); assertEquals(1,base.laneAt(29,9,9)); assertEquals(2,base.laneAt(40,5,5));
        assertEquals(-1,base.laneAt(15,0,0),"Between lanes"); assertEquals(-1,base.laneAt(20,10,0));
        assertEquals(Region.of(40,0,0,49,9,9),base.laneRegion(2));
    }
    @Test void lanesDoNotChangeTheRevisionButOverlapsAreProblems() {
        Course base=course();
        assertTrue(base.sameGameplay(base.edit().lanes(List.of(new BlockPos(50,0,0))).build()));
        Course overlapping=base.edit().lanes(List.of(new BlockPos(5,0,0))).build();
        assertTrue(overlapping.lanesOverlap());
        assertEquals(List.of("problem.lane-overlap"),overlapping.problems());
        assertFalse(base.edit().lanes(List.of(new BlockPos(10,0,0))).build().lanesOverlap(),"Touching is fine");
        assertThrows(IllegalArgumentException.class,() -> base.edit().lanes(List.of(BlockPos.ZERO)).build(),"Lane 1 is not listed");
        assertThrows(IllegalArgumentException.class,() -> base.edit().lanes(List.of(new BlockPos(50,0,0),new BlockPos(50,0,0))).build());
        List<BlockPos> tooMany=new ArrayList<>(); for(int i=1;i<Course.MAX_LANES;i++) tooMany.add(new BlockPos(i*20,0,0));
        assertEquals(Course.MAX_LANES,base.edit().lanes(tooMany).build().laneCount());
        tooMany.add(new BlockPos(99999,0,0));
        assertThrows(IllegalArgumentException.class,() -> base.edit().lanes(tooMany).build());
        assertEquals(List.of(),base.edit().lanes(List.of(new BlockPos(50,0,0))).build().withRegion(UUID.randomUUID(),Region.of(0,0,0,1,1,1)).lanes(),
                "Another world drops the lanes");
    }
    @Test void lanesPersistAndOldFilesHaveNone() throws Exception {
        Course base=course().edit().lanes(List.of(new BlockPos(0,0,30),new BlockPos(0,0,60))).build();
        new CourseStore(dir).create(base);
        assertEquals(base,new CourseStore(dir).get("c"));
        String old=Files.readString(dir.resolve("c.json")).replaceAll("(?s),\\s*\"lanes\": \\[.*?\\]","");
        assertFalse(old.contains("lanes"));
        Files.writeString(dir.resolve("c.json"),old);
        Course loaded=new CourseStore(dir).get("c");
        assertEquals(List.of(),loaded.lanes()); assertEquals(1,loaded.laneCount());
    }
}
