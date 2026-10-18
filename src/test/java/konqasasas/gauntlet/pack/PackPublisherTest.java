package konqasasas.gauntlet.pack;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackPublisherTest {
    @TempDir Path root;
    @TempDir Path web;

    private PackPublisher publisher() { return new PackPublisher(root,web,URI.create("https://catenaonline.net/packs")); }
    private void write(String source,String content) throws Exception {
        Path f=root.resolve(source);
        Files.createDirectories(f.getParent());
        Files.writeString(f,content);
    }

    @Test void publishesUnderTheHashWithAStableId() throws Exception {
        write("plugins/BetterModel/build.zip","model");
        write("plugins/ParkourGauntlet/menu/resourcepack.zip","menu");
        List<PackPublisher.Published> packs=publisher().refresh(List.of("plugins/BetterModel/build.zip","plugins/ParkourGauntlet/menu/resourcepack.zip"));
        assertEquals(2,packs.size());
        PackPublisher.Published model=packs.getFirst();
        assertEquals(PackPublisher.sha1(root.resolve("plugins/BetterModel/build.zip")),model.sha1());
        assertEquals(40,model.sha1().length());
        assertEquals(URI.create("https://catenaonline.net/packs/"+model.sha1()+".zip"),model.uri());
        assertEquals("model",Files.readString(web.resolve(model.sha1()+".zip")));
        assertNotEquals(model.id(),packs.get(1).id(),"Each source is its own pack");
    }
    @Test void aChangedFileGetsANewUrlButKeepsItsId() throws Exception {
        PackPublisher p=publisher();
        write("a.zip","one");
        PackPublisher.Published first=p.refresh(List.of("a.zip")).getFirst();
        write("a.zip","two!");
        Files.setLastModifiedTime(root.resolve("a.zip"),FileTime.from(Instant.now().plusSeconds(5)));
        PackPublisher.Published second=p.refresh(List.of("a.zip")).getFirst();
        assertNotEquals(first.sha1(),second.sha1());
        assertNotEquals(first.uri(),second.uri());
        assertEquals(first.id(),second.id(),"The client replaces the old version");
        assertEquals(List.of(second),p.refresh(List.of("missing.zip","a.zip")),"Missing sources are skipped");
    }
    @Test void anUnchangedFileIsNotHashedAgain() throws Exception {
        PackPublisher p=publisher();
        write("a.zip","one");
        FileTime time=Files.getLastModifiedTime(root.resolve("a.zip"));
        PackPublisher.Published first=p.refresh(List.of("a.zip")).getFirst();
        // Same size and time: the cached hash is used, so the web copy is what was published first.
        Files.writeString(root.resolve("a.zip"),"two");
        Files.setLastModifiedTime(root.resolve("a.zip"),time);
        assertEquals(first.sha1(),p.refresh(List.of("a.zip")).getFirst().sha1());
    }
    @Test void pruneKeepsCurrentAndRecentPacks() throws Exception {
        PackPublisher p=publisher();
        write("a.zip","current");
        List<PackPublisher.Published> current=p.refresh(List.of("a.zip"));
        Path old=web.resolve("0000000000000000000000000000000000000000.zip"), recent=web.resolve("1111111111111111111111111111111111111111.zip");
        Files.writeString(old,"old"); Files.writeString(recent,"recent");
        Instant now=Instant.now();
        Files.setLastModifiedTime(old,FileTime.from(now.minus(Duration.ofHours(2))));
        assertEquals(1,p.prune(current,Duration.ofHours(1),now));
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(recent),"Someone may still be downloading it");
        assertTrue(Files.exists(web.resolve(current.getFirst().sha1()+".zip")));
    }
}
