package konqasasas.gauntlet.play;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayStoresTest {
    @TempDir Path dir;

    @Test void bestIsTheFastestClearPerCourseAndPlayer() throws Exception {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(); Path file=dir.resolve("records.jsonl");
        var store=new RecordStore(file);
        store.append(new RunRecord("c1",a,RunRecord.Result.CLEAR,300,0,0,0,1));
        store.append(new RunRecord("c1",a,RunRecord.Result.RETIRE,10,0,0,0,2));
        store.append(new RunRecord("c1",a,RunRecord.Result.CLEAR,250,0,0,0,3));
        store.append(new RunRecord("c2",a,RunRecord.Result.CLEAR,999,0,0,0,4));
        store.append(new RunRecord("c1",b,RunRecord.Result.FAIL,5,1,2,3,5));
        var loaded=new RecordStore(file);
        assertEquals(OptionalLong.of(250),loaded.best("c1",a)); assertEquals(OptionalLong.of(999),loaded.best("c2",a));
        assertTrue(loaded.best("c1",b).isEmpty());
        assertEquals(5,Files.readAllLines(file).size());
    }
    @Test void tornLineDoesNotLoseOtherRecords() throws Exception {
        UUID a=UUID.randomUUID(); Path file=dir.resolve("records.jsonl");
        new RecordStore(file).append(new RunRecord("c",a,RunRecord.Result.CLEAR,40,0,0,0,1));
        Files.writeString(file,"{\"course\":\"c\",\"pla",StandardOpenOption.APPEND);
        var loaded=new RecordStore(file);
        assertEquals(1,loaded.invalidLines()); assertEquals(OptionalLong.of(40),loaded.best("c",a));
    }
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
}
