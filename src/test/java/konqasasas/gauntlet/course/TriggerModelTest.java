package konqasasas.gauntlet.course;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TriggerModelTest {
    @TempDir Path dir;
    private final UUID author=UUID.randomUUID(),world=UUID.randomUUID();

    @Test void mobSpecAcceptsPastedSummonCommands() {
        MobSpec spec=MobSpec.parse("summon minecraft:strider -84 -56 -37 {Age:0,Passengers:[],Health:1,Silent:1b,attributes:[{id:\"minecraft:movement_speed\",base:0}]}");
        assertNotNull(spec);
        assertEquals("minecraft:strider",spec.type());
        assertArrayEquals(new double[]{-84,-56,-37},spec.position());
        assertTrue(spec.stored().startsWith("minecraft:strider {Age:0"));
        assertEquals("{Tags:[\"a\",\"b\"],Age:0,Passengers:[],Health:1,Silent:1b,attributes:[{id:\"minecraft:movement_speed\",base:0}]}",spec.nbtWithTags("a","b"));
        MobSpec bare=MobSpec.parse("zombie");
        assertEquals("minecraft:zombie",bare.type()); assertNull(bare.position());
        assertEquals("{Tags:[\"x\"]}",bare.nbtWithTags("x"));
        assertEquals("{Tags:[\"x\"]}",MobSpec.parse("zombie {}").nbtWithTags("x"));
        assertNotNull(MobSpec.parse("/summon slime 1.5 64 -2.25"));
    }
    @Test void mobSpecRejectsBadInput() {
        for(String bad:new String[]{"","  ","zombie {Tags:[\"mine\"]}","zombie ~ ~ ~","zombie 1 2","zom bie","zombie {unclosed"})
            assertNull(MobSpec.parse(bad),bad);
    }
    @Test void itemTagsRoundTripAndDistinguishLevels() {
        ItemRef one=new ItemRef("tabc",1),two=new ItemRef("tabc",2);
        assertEquals(one,ItemRef.fromTag(one.tag())); assertNotEquals(one,two);
        assertTrue(ItemRef.fromTag(ItemRef.scaffold().tag()).isScaffold());
        assertNull(ItemRef.fromTag("nolevel")); assertNull(ItemRef.fromTag("t:9")); assertNull(ItemRef.fromTag(null));
        assertThrows(IllegalArgumentException.class,() -> new ItemRef("t",4));
    }
    @Test void actionsValidateTheirOwnFields() {
        assertThrows(IllegalArgumentException.class,() -> TriggerAction.give(null,TriggerAction.Placement.SLOT1));
        assertThrows(IllegalArgumentException.class,() -> TriggerAction.mob("zombie {Tags:[1]}",new Spot(0,0,0,0,0)));
        assertThrows(IllegalArgumentException.class,() -> TriggerAction.sound("x",5,1));
        TriggerAction sound=TriggerAction.sound("minecraft:block.bell.use",1,1).withSound(9,0);
        assertEquals(TriggerAction.MAX_VOLUME,sound.volume()); assertEquals(TriggerAction.MIN_PITCH,sound.pitch());
        assertEquals(0,TriggerAction.Placement.SLOT1.slot()); assertEquals(2,TriggerAction.Placement.SLOT3.slot()); assertEquals(-1,TriggerAction.Placement.SLOT4.slot()); assertEquals(-1,TriggerAction.Placement.EMPTY.slot());
    }
    @Test void triggersLoadoutAndFailFlagsPersist() throws Exception {
        BlockPos plate=new BlockPos(2,1,2);
        Trigger trigger=new Trigger(plate,List.of(
                TriggerAction.give(new ItemRef("tmace",3),TriggerAction.Placement.SLOT2),
                TriggerAction.remove(new ItemRef("tmace",0),true),
                TriggerAction.mob("minecraft:strider {Age:0}",new Spot(3,1,3,0,0)),
                TriggerAction.sound("minecraft:block.bell.use",.5f,1.25f)));
        Course course=Course.create("c",author,world).withRegion(world,Region.of(0,0,0,9,9,9)).withStart(new Spot(1,1,1,0,0))
                .toggleGoal(new BlockPos(8,0,8)).edit().trigger(trigger).loadout(1,new ItemRef("tmace",1)).failLava(false).build();
        var store=new CourseStore(dir); store.create(course);
        Course loaded=new CourseStore(dir).get("c");
        assertEquals(course,loaded);
        assertEquals(trigger,loaded.trigger(plate));
        assertEquals(Arrays.asList(ItemRef.scaffold(),new ItemRef("tmace",1),null),loaded.loadout());
        assertFalse(loaded.failLava()); assertTrue(loaded.failOutside());
        assertEquals(List.of(),loaded.problems());
    }
    @Test void courseRulesForTriggersAndLoadout() {
        Course course=Course.create("c",author,world).withRegion(world,Region.of(0,0,0,9,9,9)).withStart(new Spot(1,1,1,0,0)).toggleGoal(new BlockPos(8,0,8));
        Trigger a=new Trigger(new BlockPos(2,1,2),List.of());
        Course withTrigger=course.edit().trigger(a).trigger(a.withAction(TriggerAction.sound("minecraft:block.bell.use",1,1))).build();
        assertEquals(1,withTrigger.triggers().size(),"Same position replaces");
        assertEquals(1,withTrigger.trigger(a.pos()).actions().size());
        assertEquals(List.of(),withTrigger.edit().removeTrigger(a.pos()).build().triggers());
        assertEquals(List.of("problem.trigger-outside"),course.edit().trigger(new Trigger(new BlockPos(20,0,0),List.of())).build().problems());
        assertEquals(List.of("problem.no-slot1"),course.edit().loadout(0,null).build().problems());
        assertEquals(List.of(),withTrigger.edit().region(UUID.randomUUID(),Region.of(0,0,0,1,1,1)).build().triggers(),"Other world drops triggers");
        assertThrows(IllegalArgumentException.class,() -> new Trigger(a.pos(),Collections.nCopies(Trigger.MAX_ACTIONS+1,TriggerAction.sound("s",1,1))));
    }
    @Test void triggerNamesAreLabelsOnly() throws Exception {
        Course course=Course.create("c",author,world).withRegion(world,Region.of(0,0,0,9,9,9)).withStart(new Spot(1,1,1,0,0))
                .toggleGoal(new BlockPos(8,0,8)).edit().trigger(new Trigger(new BlockPos(2,1,2),List.of())).build();
        Course named=course.edit().trigger(course.trigger(new BlockPos(2,1,2)).withName("メイス区間")).build();
        assertTrue(named.sameGameplay(course),"A name does not change the revision");
        assertFalse(named.edit().trigger(named.trigger(new BlockPos(2,1,2)).withAction(TriggerAction.sound("s",1,1))).build().sameGameplay(course));
        var store=new CourseStore(dir); store.create(named);
        assertEquals("メイス区間",new CourseStore(dir).get("c").trigger(new BlockPos(2,1,2)).name());
        assertNull(new Trigger(new BlockPos(0,0,0),List.of(),"  ").name(),"Blank means no name");
        assertThrows(IllegalArgumentException.class,() -> new Trigger(new BlockPos(0,0,0),List.of(),"x".repeat(Trigger.MAX_NAME_LENGTH+1)));
        Trigger t=new Trigger(new BlockPos(0,0,0),List.of(),"n");
        assertEquals("n",t.withAction(TriggerAction.sound("s",1,1)).withoutAction(0).withActions(List.of()).name(),"Edits keep the name");
    }
    @Test void freeTriggersPersistPerWorldAndBrokenFileIsPreserved() throws Exception {
        Path file=dir.resolve("triggers.json");
        UUID other=UUID.randomUUID();
        BlockPos plate=new BlockPos(5,64,-3);
        var store=new FreeTriggerStore(file);
        store.put(world,new Trigger(plate,List.of(TriggerAction.give(new ItemRef("tmace",2),TriggerAction.Placement.SLOT1)),"入口"));
        store.put(other,new Trigger(plate,List.of()));
        store.put(world,store.get(world,plate).withAction(TriggerAction.sound("minecraft:block.bell.use",1,1)));
        var loaded=new FreeTriggerStore(file);
        assertEquals(2,loaded.all().size(),"Same position in another world is another trigger");
        assertEquals(store.get(world,plate),loaded.get(world,plate));
        assertEquals(2,loaded.get(world,plate).actions().size());
        loaded.remove(List.of(new FreeTriggerStore.Key(world,plate)));
        assertNull(new FreeTriggerStore(file).get(world,plate));
        assertNotNull(new FreeTriggerStore(file).get(other,plate));
        Files.writeString(file,"{nope");
        assertThrows(IOException.class,() -> new FreeTriggerStore(file)); assertEquals("{nope",Files.readString(file));
    }
    @Test void templatesPersistAndBrokenFileIsPreserved() throws Exception {
        Path file=dir.resolve("templates.json");
        var store=new TemplateStore(file);
        var mace=store.add("ウィンドバーストメイス","AAAA",true);
        store.put(mace.withName("メイス").withUnbreakable(false));
        store.add("ロケット","BBBB",false);
        var loaded=new TemplateStore(file);
        assertEquals(2,loaded.all().size());
        assertEquals("メイス",loaded.get(mace.id()).name()); assertFalse(loaded.get(mace.id()).unbreakable());
        loaded.remove(mace.id());
        assertNull(new TemplateStore(file).get(mace.id()));
        Files.writeString(file,"{nope");
        assertThrows(IOException.class,() -> new TemplateStore(file)); assertEquals("{nope",Files.readString(file));
    }
}
