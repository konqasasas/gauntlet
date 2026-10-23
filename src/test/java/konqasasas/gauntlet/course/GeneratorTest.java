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
        List<Generator.Piece> pieces=List.of(piece("a",1,5,2),piece("b",1,4,-1),piece("c",1,6,1),piece("d",1,3,0));
        Generator.Plan plan=Generator.plan(pieces,Generator.Profile.only(4,1,0),NO_JOINT,new BlockPos(100,64,0),7);
        assertTrue(plan.ok(),plan.failure());
        assertEquals(new BlockPos(100,64,0),plan.placed().getFirst().start());
        for(int i=1;i<plan.placed().size();i++) assertEquals(plan.placed().get(i-1).exit(),plan.placed().get(i).start(),"Seamless at "+i);
        assertEquals(4,plan.placed().stream().map(p -> p.piece().id()).distinct().count(),"Each module once per course");
        assertEquals("no-candidate",Generator.plan(pieces,Generator.Profile.only(5,1,0),NO_JOINT,BlockPos.ZERO,7).failure(),"Not enough modules for five");
    }
    @Test void jointsAreFlatAndTheSeedRepeats() {
        List<Generator.Piece> pieces=new ArrayList<>();
        for(int d=1;d<=5;d++) for(int k=0;k<5;k++) pieces.add(piece("m"+d+"-"+k,d,5,k%2));
        Generator.Settings joint=new Generator.Settings(6,2,3,-64,319); // no hard-streak limit here
        Generator.Plan plan=Generator.plan(pieces,new Generator.Profile(5,List.of(1.0,1.0,1.0,1.0,1.0),0),joint,BlockPos.ZERO,42);
        assertTrue(plan.ok(),plan.failure());
        for(int i=1;i<plan.placed().size();i++)
            assertEquals(plan.placed().get(i-1).exit().plus(new BlockPos(0,0,4)),plan.placed().get(i).start(),"Three joint blocks between");
        assertEquals(4*4,plan.joints().size(),"The old goal block and three more, four times");
        List<Integer> d=plan.placed().stream().map(p -> p.piece().difficulty()).toList();
        assertEquals(d.stream().sorted().toList(),d,"No bumpiness: easy to hard in order");
        assertEquals(plan.placed().stream().map(p -> p.piece().id()).toList(),
                Generator.plan(pieces,new Generator.Profile(5,List.of(1.0,1.0,1.0,1.0,1.0),0),joint,BlockPos.ZERO,42).placed().stream().map(p -> p.piece().id()).toList(),"Same seed, same course");
    }
    @Test void distributionAndBumpiness() {
        Random random=new Random(5);
        Generator.Profile smooth=new Generator.Profile(2000,List.of(1.0,3.0,3.0,2.0,1.0),0);
        List<Integer> t=smooth.targets(random);
        assertEquals(t.stream().sorted().toList(),t,"Bumpiness 0: in order");
        long twos=t.stream().filter(x -> x==2).count(),fives=t.stream().filter(x -> x==5).count();
        assertTrue(twos>500 && twos<700,"About 30%: "+twos); assertTrue(fives>140 && fives<260,"About 10%: "+fives);
        List<Integer> bumpy=new Generator.Profile(30,List.of(1.0,1.0,1.0,1.0,1.0),2).targets(new Random(3));
        int downs=0; for(int i=1;i<bumpy.size();i++) if(bumpy.get(i)<bumpy.get(i-1)) downs++;
        assertTrue(downs>0,"Ups and downs");
        assertTrue(bumpy.subList(0,10).stream().mapToInt(Integer::intValue).average().orElse(0)<bumpy.subList(20,30).stream().mapToInt(Integer::intValue).average().orElse(0),"Still a climb");
        assertThrows(IllegalArgumentException.class,() -> new Generator.Profile(3,List.of(0.0,0.0,0.0,0.0,0.0),1));
        assertThrows(IllegalArgumentException.class,() -> new Generator.Profile(3,List.of(1.0,1.0),1));
        assertThrows(IllegalArgumentException.class,() -> new Generator.Profile(3,List.of(1.0,1.0,1.0,1.0,1.0),-1));
    }
    @Test void bandsHoldEveryTime() {
        List<Generator.Band> bands=List.of(new Generator.Band(4,5,1,2),new Generator.Band(1,1,0,1),Generator.Band.parse("3-3:2-2"));
        // Mostly easy, so the hard band and the one-difficulty-1 limit must be enforced.
        Generator.Profile p=new Generator.Profile(6,List.of(10.0,3.0,1.0,0.0,0.0),1,bands);
        Random random=new Random(11);
        for(int run=0;run<500;run++) {
            List<Integer> t=p.targets(random);
            assertEquals(6,t.size());
            long hard=t.stream().filter(x -> x>=4).count(),ones=t.stream().filter(x -> x==1).count(),threes=t.stream().filter(x -> x==3).count();
            assertTrue(hard>=1 && hard<=2,"Hard 1-2: "+t); assertTrue(ones<=1,"At most one 1: "+t); assertEquals(2,threes,"Exactly two 3: "+t);
        }
        assertEquals("4-5:1-2",bands.getFirst().text());
        Generator.Profile impossible=new Generator.Profile(3,List.of(1.0,1.0,1.0,1.0,1.0),1,List.of(new Generator.Band(1,2,2,3),new Generator.Band(4,5,2,3)));
        Generator.BandConflict e=assertThrows(Generator.BandConflict.class,() -> impossible.targets(new Random(1)));
        assertNotNull(e.band);
        assertThrows(IllegalArgumentException.class,() -> new Generator.Band(3,2,0,1));
        assertThrows(IllegalArgumentException.class,() -> new Generator.Band(1,5,2,1));
        assertThrows(IllegalArgumentException.class,() -> Generator.Band.parse("4-5"));
    }
    @Test void halfTheModulesAreMirroredAroundTheirStart() {
        // An L-shaped module: forward 3, then 2 to the +X side.
        Set<BlockPos> solid=new HashSet<>(Set.of(new BlockPos(0,0,0),new BlockPos(0,0,1),new BlockPos(0,0,2),new BlockPos(1,0,2),new BlockPos(2,0,2)));
        Generator.Piece l=new Generator.Piece("l",1,BlockPos.ZERO,new BlockPos(2,0,2),Region.of(0,0,0,2,3,2),solid);
        Generator.Piece m=l.mirrored();
        assertEquals(BlockPos.ZERO,m.start()); assertEquals(new BlockPos(-2,0,2),m.exit());
        assertEquals(Region.of(-2,0,0,0,3,2),m.region());
        assertTrue(m.solid().contains(new BlockPos(-2,0,2)) && !m.solid().contains(new BlockPos(2,0,2)));
        List<Generator.Piece> many=new ArrayList<>();
        for(int i=0;i<40;i++) many.add(new Generator.Piece("l"+i,1,BlockPos.ZERO,new BlockPos(2,0,2),Region.of(0,0,0,2,3,2),solid));
        Generator.Plan plan=Generator.plan(many,Generator.Profile.only(40,1,0),NO_JOINT,BlockPos.ZERO,9);
        assertTrue(plan.ok(),plan.failure());
        long mirrored=plan.placed().stream().filter(Generator.Placed::mirrored).count();
        assertTrue(mirrored>5 && mirrored<35,"About half: "+mirrored);
        for(int i=1;i<plan.placed().size();i++) assertEquals(plan.placed().get(i-1).exit(),plan.placed().get(i).start());
    }
    @Test void noMoreThanTwoHardModulesInARow() {
        List<Generator.Piece> pieces=new ArrayList<>();
        for(int i=0;i<6;i++) pieces.add(piece("h"+i,i%2==0 ? 5 : 4,4,0));
        for(int i=0;i<4;i++) pieces.add(piece("easy"+i,2,4,0));
        Generator.Plan plan=Generator.plan(pieces,Generator.Profile.only(9,5,0),NO_JOINT,BlockPos.ZERO,1);
        assertTrue(plan.ok(),plan.failure());
        int run=0;
        for(Generator.Placed p:plan.placed()) { run=p.piece().difficulty()>=4 ? run+1 : 0; assertTrue(run<=2,"Hard streak "+run); }
    }
    @Test void stopsAndSaysWhereWhenNothingFits() {
        Generator.Plan high=Generator.plan(List.of(piece("up",1,3,10),piece("up2",1,3,10),piece("up3",1,3,10),piece("up4",1,3,10),piece("up5",1,3,10)),Generator.Profile.only(5,1,0),new Generator.Settings(4,2,0,0,30),BlockPos.ZERO,3);
        assertFalse(high.ok());
        assertEquals("height",high.failure()); assertEquals(2,high.failedAt());
        assertEquals("no-modules",Generator.plan(List.of(),Generator.Profile.only(2,1,0),NO_JOINT,BlockPos.ZERO,0).failure());
        // A module whose blocks reach back over the previous one.
        Set<BlockPos> back=new HashSet<>(Set.of(new BlockPos(0,0,0),new BlockPos(0,0,-2),new BlockPos(0,0,2)));
        Generator.Piece hook=new Generator.Piece("hook",1,BlockPos.ZERO,new BlockPos(0,0,2),Region.of(0,0,-2,0,2,2),back);
        Generator.Piece hook2=new Generator.Piece("hook2",1,BlockPos.ZERO,new BlockPos(0,0,2),Region.of(0,0,-2,0,2,2),back);
        Generator.Plan overlap=Generator.plan(List.of(hook,hook2),Generator.Profile.only(2,1,0),NO_JOINT,BlockPos.ZERO,0);
        assertEquals("overlap",overlap.failure()); assertEquals(1,overlap.failedAt());
    }
}
