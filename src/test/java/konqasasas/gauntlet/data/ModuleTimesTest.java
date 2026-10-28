package konqasasas.gauntlet.data;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModuleTimesTest {
    @TempDir Path dir;
    private static final Logger LOG=Logger.getAnonymousLogger();

    @Test void meanMedianAndSlowerQuarter() {
        ModuleTimes.Summary s=ModuleTimes.summarize(new long[]{120,100,140,200},2);
        assertEquals(4,s.passes()); assertEquals(2,s.players());
        assertEquals(140,s.mean(),1e-9);
        assertEquals(130,s.median(),1e-9,"Between the two middle times");
        assertEquals(155,s.slowQuarter(),1e-9);
        assertEquals(100,ModuleTimes.summarize(new long[]{100},1).slowQuarter(),1e-9);
        assertNull(ModuleTimes.summarize(new long[0],0));
    }
    @Test void passesAreKeptPerRevisionAndSurviveRestart() throws Exception {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        try(Database db=Database.open(dir.resolve("g.db"),LOG)) {
            ModuleTimes times=new ModuleTimes(db);
            times.add("m",1,a,100,false,true); times.add("m",1,a,120,true,false); times.add("m",1,b,140,false,true);
            times.add("m",0,b,999,false,true);
            assertEquals(3,times.summary("m",1).passes());
            assertEquals(2,times.summary("m",1).players());
            assertNull(times.summary("m",2));
        }
        try(Database db=Database.open(dir.resolve("g.db"),LOG)) {
            ModuleTimes times=new ModuleTimes(db); times.load();
            assertEquals(120,times.summary("m",1).median(),1e-9);
            assertEquals(999,times.summary("m",0).mean(),1e-9);
        }
    }
}
