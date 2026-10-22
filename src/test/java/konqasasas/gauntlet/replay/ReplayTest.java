package konqasasas.gauntlet.replay;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import konqasasas.gauntlet.data.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplayTest {
    @TempDir Path dir;
    private final UUID a=UUID.randomUUID(),b=UUID.randomUUID();

    private static Replay replay(UUID run,UUID player,String course,int rev,long ticks) {
        List<Replay.Frame> frames=List.of(new Replay.Frame(1.5,64,2.25,90,10,false,true,false,"minecraft:polished_andesite"),
                new Replay.Frame(1.6,64.2,2.25,91,9,true,false,true,""));
        List<Replay.BlockChange> blocks=List.of(new Replay.BlockChange(1,3,63,2,"minecraft:polished_andesite"),new Replay.BlockChange(1,4,64,2,"minecraft:water[level=0]"));
        List<List<Replay.Thing>> things=List.of(List.of(),List.of(new Replay.Thing(0,"minecraft:falling_block",5.5,70,2.5,0,"minecraft:sand"),new Replay.Thing(1,"minecraft:zombie",8,64,2,180,"")));
        return new Replay(run,player,course,rev,ticks,frames,blocks,things);
    }
    @Test void writesAndReadsBack() throws IOException {
        Replay r=replay(UUID.randomUUID(),a,"x",3,40);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        r.write(out);
        assertEquals(r,Replay.read(new ByteArrayInputStream(out.toByteArray())));
    }
    private static ReplayStore.Entry e(UUID player,String course,int rev,long ticks,long at,long bytes) {
        return new ReplayStore.Entry(UUID.randomUUID(),player,course,rev,ticks,at,bytes);
    }
    @Test void keepsTheBestAndTheLatestThree() {
        ReplayStore.Entry best=e(a,"x",1,100,1,10);
        List<ReplayStore.Entry> group=new ArrayList<>(List.of(best,e(a,"x",1,300,2,10),e(a,"x",1,200,3,10),e(a,"x",1,250,4,10),e(a,"x",1,260,5,10),e(a,"x",1,270,6,10)));
        ReplayStore.Entry otherRev=e(a,"x",2,500,7,10),other=e(b,"x",1,90,8,10);
        group.add(otherRev); group.add(other);
        List<UUID> drop=ReplayStore.drop(group,3,Long.MAX_VALUE);
        assertEquals(Set.of(group.get(1).run(),group.get(2).run()),new HashSet<>(drop),"The two oldest that are not the best");
        // Over the cap: the oldest non-best go first, then the oldest of all.
        List<UUID> capped=ReplayStore.drop(group,10,30);
        assertFalse(capped.contains(best.run()) || capped.contains(otherRev.run()) || capped.contains(other.run()),"Bests stay while others can go");
        assertEquals(5,capped.size());
    }
    @Test void savesListsAndLoadsByPrefix() throws Exception {
        try(Database db=Database.open(dir.resolve("g.db"),Logger.getAnonymousLogger())) {
            ReplayStore store=new ReplayStore(db,dir.resolve("replays"),1,Long.MAX_VALUE);
            Replay slow=replay(UUID.randomUUID(),a,"x",1,300),fast=replay(UUID.randomUUID(),a,"x",1,100),late=replay(UUID.randomUUID(),a,"x",1,200);
            assertEquals(List.of(),store.save(slow)); Thread.sleep(5);
            assertEquals(List.of(slow.run()),store.save(fast),"Neither the best nor the latest any more"); Thread.sleep(5);
            assertEquals(List.of(),store.save(late),"Best (fast) and the latest one (late) stay");
            assertFalse(Files.exists(dir.resolve("replays").resolve(slow.run()+".replay")));
            assertEquals(List.of(late.run(),fast.run()),store.entries(a).stream().map(ReplayStore.Entry::run).toList());
            assertEquals(fast,store.load(fast.run().toString().substring(0,8)));
            assertNull(store.load("abc"),"An id needs 8 characters");
        }
    }
}
