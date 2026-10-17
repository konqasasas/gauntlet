package konqasasas.gauntlet.play;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayStoresTest {
    @TempDir Path dir;

    @Test void returnStateSurvivesRestartAndRemoval() throws Exception {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),world=UUID.randomUUID(); Path file=dir.resolve("participants.json");
        var saved=new ReturnStore.Saved(world,1.5,64,-3.25,90,10,"CREATIVE",true,"AAEC");
        var store=new ReturnStore(file); store.put(a,saved); store.put(b,saved);
        var loaded=new ReturnStore(file);
        assertEquals(saved,loaded.get(a));
        loaded.remove(a);
        assertNull(new ReturnStore(file).get(a)); assertEquals(saved,new ReturnStore(file).get(b));
    }
    @Test void brokenReturnFileIsPreserved() throws Exception {
        Path file=dir.resolve("participants.json"); Files.writeString(file,"{bad");
        assertThrows(IOException.class,() -> new ReturnStore(file)); assertEquals("{bad",Files.readString(file));
    }
    @Test void runRecordRejectsBadValues() {
        UUID id=UUID.randomUUID(),player=UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,() -> new RunRecord(id,"c",0,player,RunRecord.Result.FAIL,null,1,null,1.5,null,0,0,0,0,null,null));
        assertThrows(IllegalArgumentException.class,() -> new RunRecord(id,"c",-1,player,RunRecord.Result.FAIL,null,1,null,null,null,0,0,0,0,null,null));
    }
}
