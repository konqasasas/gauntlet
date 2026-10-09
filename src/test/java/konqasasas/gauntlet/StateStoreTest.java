package konqasasas.gauntlet;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StateStoreTest {
    @TempDir Path dir;
    @Test void checkpointsSurviveRestartWithViewAndIndependentOwners() throws Exception {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),world=UUID.randomUUID();
        var first=new Checkpoint(world,12.125,64.5,-17.25,179.5f,-30.25f);
        var other=new Checkpoint(world,-4,80,10,-90,40);
        var store=new StateStore(dir.resolve("state.json")); store.checkpoint(a,first); store.checkpoint(b,other);
        var loaded=new StateStore(dir.resolve("state.json"));
        assertEquals(first,loaded.checkpoint(a)); assertEquals(other,loaded.checkpoint(b));
        loaded.checkpoint(a,new Checkpoint(world,0,0,0,0,0));
        assertEquals(other,new StateStore(dir.resolve("state.json")).checkpoint(b));
    }
    @Test void cleanupJournalPreservesOriginalBlocksAndOtherOwners() throws Exception {
        UUID world=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID();
        var one=new PlacedBlock(a,new BlockKey(world,0,64,0),"minecraft:water[level=0]","minecraft:polished_andesite");
        var two=new PlacedBlock(b,new BlockKey(world,1,64,0),"minecraft:air","minecraft:polished_andesite");
        var store=new StateStore(dir.resolve("state.json")); store.add(one); store.add(two);
        var loaded=new StateStore(dir.resolve("state.json"));
        assertEquals(List.of(one),loaded.blocks(a));
        assertThrows(IOException.class,() -> loaded.add(one));
        loaded.remove(List.of(one.block()));
        var restarted=new StateStore(dir.resolve("state.json"));
        assertFalse(restarted.contains(one.block())); assertEquals(List.of(two),restarted.blocks(b));
    }
    @Test void invalidStateFailsWithoutOverwritingTheFile() throws Exception {
        Path file=dir.resolve("state.json"); Files.writeString(file,"{broken JSON");
        assertThrows(IOException.class,() -> new StateStore(file)); assertEquals("{broken JSON",Files.readString(file));
    }
    @Test void failedDiskWriteDoesNotRegisterBlockOrCheckpoint() throws Exception {
        Path parent=dir.resolve("not-a-directory"); Files.writeString(parent,"keep");
        var store=new StateStore(parent.resolve("state.json")); UUID owner=UUID.randomUUID(),world=UUID.randomUUID();
        var block=new PlacedBlock(owner,new BlockKey(world,0,0,0),"minecraft:air","minecraft:polished_andesite");
        assertThrows(IOException.class,() -> store.add(block)); assertFalse(store.contains(block.block()));
        assertThrows(IOException.class,() -> store.checkpoint(owner,new Checkpoint(world,0,0,0,0,0)));
        assertNull(store.checkpoint(owner)); assertEquals("keep",Files.readString(parent));
    }
    @Test void nonFiniteCoordinatesAreRejected() {
        UUID world=UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,() -> new Checkpoint(world,Double.NaN,0,0,0,0));
        assertThrows(IllegalArgumentException.class,() -> new Checkpoint(world,0,0,0,0,Float.POSITIVE_INFINITY));
    }
}
