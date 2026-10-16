package konqasasas.gauntlet.course;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CourseStoreTest {
    @TempDir Path dir;
    private final UUID author=UUID.randomUUID(),world=UUID.randomUUID();

    @Test void coursesSurviveRestartWithAllFields() throws Exception {
        Course course=Course.create("rank1-01",author,world).withName("はじめの一歩")
                .withRegion(world,Region.of(10,60,-5,0,70,5)).withStart(new Spot(1.5,61,0.5,90,-10)).toggleGoal(new BlockPos(9,60,0))
                .withTimeLimit(45).withStatus(CourseStatus.TESTING);
        var store=new CourseStore(dir); store.create(course);
        assertEquals(course,new CourseStore(dir).get("rank1-01"));
        assertTrue(Files.readString(dir.resolve("rank1-01.json")).contains("はじめの一歩"),"Names stay human-readable");
    }
    @Test void createRefusesExistingIdAndSaveRequiresExistingCourse() throws Exception {
        var store=new CourseStore(dir); store.create(Course.create("a",author,world));
        assertThrows(IOException.class,() -> store.create(Course.create("a",author,world)));
        assertThrows(IOException.class,() -> store.save(Course.create("b",author,world)));
    }
    @Test void brokenFileIsSkippedPreservedAndItsIdReserved() throws Exception {
        Files.createDirectories(dir); Files.writeString(dir.resolve("broken.json"),"{oops");
        new CourseStore(dir).create(Course.create("ok",author,world));
        var store=new CourseStore(dir);
        assertEquals(List.of("ok"),store.all().stream().map(Course::id).toList());
        assertEquals(1,store.invalidFiles().size());
        assertThrows(IOException.class,() -> store.create(Course.create("broken",author,world)));
        assertEquals("{oops",Files.readString(dir.resolve("broken.json")));
    }
    @Test void fileNameMustMatchCourseId() throws Exception {
        var store=new CourseStore(dir); store.create(Course.create("x",author,world));
        Files.move(dir.resolve("x.json"),dir.resolve("y.json"));
        var loaded=new CourseStore(dir);
        assertNull(loaded.get("x")); assertNull(loaded.get("y")); assertEquals(1,loaded.invalidFiles().size());
    }
    @Test void deleteKeepsACopy() throws Exception {
        var store=new CourseStore(dir); store.create(Course.create("gone",author,world)); store.delete("gone");
        assertNull(store.get("gone")); assertFalse(Files.exists(dir.resolve("gone.json")));
        try(var kept=Files.list(dir.resolve("deleted"))) { assertEquals(1,kept.count()); }
        assertNull(new CourseStore(dir).get("gone"));
    }
    @Test void idsAreRestricted() {
        assertTrue(CourseStore.validId("rank1_boss-2"));
        for(String bad:new String[]{"","Upper","a b","../x","a.json","日本語","x".repeat(33)}) assertFalse(CourseStore.validId(bad),bad);
    }
}
