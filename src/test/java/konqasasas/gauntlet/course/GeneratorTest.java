package konqasasas.gauntlet.course;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class GeneratorTest {
    /** A flat module along +Z: a floor from z=0 to z=len-1 at y=0, start on (0,0,0), goal at (0,rise,len-1). */
    private static Generator.Piece piece(String id,int difficulty,int len,int rise) {
        Set<BlockPos> solid=new HashSet<>();
        for(int z=0;z<len;z++) solid.add(new BlockPos(0,z==len-1 ? rise : 0,z));
        return new Generator.Piece(id,difficulty,new BlockPos(0,0,0),new BlockPos(0,rise,len-1),Region.of(-1,0,0,1,Math.max(rise,0)+3,len-1),solid);
    }
    private static final Generator.Settings NO_JOINT=new Generator.Settings(4,2,0,-64,319);

    @Test void eachStartLandsOnThePreviousGoalHeightIncluded() {
        List<Generator.Piece> pieces=List.of(piece("a",1,5,2),piece("b",1,4,-1));
        Generator.Plan plan=Generator.plan(pieces,new Generator.Profile(4,1,1),NO_JOINT,new BlockPos(100,64,0),7);
        assertTrue(plan.ok(),plan.failure());
        assertEquals(new BlockPos(100,64,0),plan.placed().getFirst().start());
        for(int i=1;i<plan.placed().size();i++) assertEquals(plan.placed().get(i-1).exit(),plan.placed().get(i).start(),"Seamless at "+i);
        for(int i=1;i<plan.placed().size();i++) assertNotEquals(plan.placed().get(i-1).piece().id(),plan.placed().get(i).piece().id(),"Never the same twice in a row");
    }
    @Test void jointsAreFlatAndTheSeedRepeats() {
        List<Generator.Piece> pieces=List.of(piece("a",1,5,0),piece("b",2,5,1),piece("c",3,5,0),piece("d",4,5,1),piece("e",5,5,0));
        Generator.Settings joint=new Generator.Settings(4,2,3,-64,319);
        Generator.Plan plan=Generator.plan(pieces,new Generator.Profile(5,1,5),joint,BlockPos.ZERO,42);
        assertTrue(plan.ok(),plan.failure());
        for(int i=1;i<plan.placed().size();i++)
            assertEquals(plan.placed().get(i-1).exit().plus(new BlockPos(0,0,4)),plan.placed().get(i).start(),"Three joint blocks between");
        assertEquals(4*4,plan.joints().size(),"The old goal block and three more, four times");
        assertEquals(List.of(1,2,3,4,5),plan.placed().stream().map(p -> p.piece().difficulty()).toList(),"Difficulty goes up in a line");
        assertEquals(plan.placed().stream().map(p -> p.piece().id()).toList(),
                Generator.plan(pieces,new Generator.Profile(5,1,5),joint,BlockPos.ZERO,42).placed().stream().map(p -> p.piece().id()).toList(),"Same seed, same course");
    }
    @Test void noMoreThanTwoHardModulesInARow() {
        List<Generator.Piece> pieces=List.of(piece("h1",5,4,0),piece("h2",5,4,0),piece("h3",4,4,0),piece("easy",2,4,0));
        Generator.Plan plan=Generator.plan(pieces,new Generator.Profile(9,5,5),NO_JOINT,BlockPos.ZERO,1);
        assertTrue(plan.ok(),plan.failure());
        int run=0;
        for(Generator.Placed p:plan.placed()) { run=p.piece().difficulty()>=4 ? run+1 : 0; assertTrue(run<=2,"Hard streak "+run); }
    }
    @Test void stopsAndSaysWhereWhenNothingFits() {
        Generator.Plan high=Generator.plan(List.of(piece("up",1,3,10),piece("up2",1,3,10)),new Generator.Profile(5,1,1),new Generator.Settings(4,2,0,0,30),BlockPos.ZERO,3);
        assertFalse(high.ok());
        assertEquals("height",high.failure()); assertEquals(2,high.failedAt());
        assertEquals("no-modules",Generator.plan(List.of(),new Generator.Profile(2,1,1),NO_JOINT,BlockPos.ZERO,0).failure());
        // A module whose blocks reach back over the previous one.
        Set<BlockPos> back=new HashSet<>(Set.of(new BlockPos(0,0,0),new BlockPos(0,0,-2),new BlockPos(0,0,2)));
        Generator.Piece hook=new Generator.Piece("hook",1,BlockPos.ZERO,new BlockPos(0,0,2),Region.of(0,0,-2,0,2,2),back);
        Generator.Piece hook2=new Generator.Piece("hook2",1,BlockPos.ZERO,new BlockPos(0,0,2),Region.of(0,0,-2,0,2,2),back);
        Generator.Plan overlap=Generator.plan(List.of(hook,hook2),new Generator.Profile(2,1,1),NO_JOINT,BlockPos.ZERO,0);
        assertEquals("overlap",overlap.failure()); assertEquals(1,overlap.failedAt());
    }
}
