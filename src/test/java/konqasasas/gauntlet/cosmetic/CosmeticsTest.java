package konqasasas.gauntlet.cosmetic;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import konqasasas.gauntlet.data.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CosmeticsTest {
    @TempDir Path dir;
    private static final Logger LOG=Logger.getAnonymousLogger();
    private final UUID a=UUID.randomUUID(),b=UUID.randomUUID();

    private Catalog catalog(String extra) throws IOException {
        Path file=dir.resolve("cosmetics.yml");
        Files.writeString(file,extra);
        return Catalog.load(getClass().getClassLoader(),file);
    }
    private static final String SHOP="""
            slots: [trail]
            items:
              trail.standard: { slot: trail, theme: standard, standard: true }
              trail.brass: { slot: trail, theme: brass, obtain: purchase }
              stage_clear.brass: { slot: stage_clear, theme: brass, obtain: purchase }
              stage_clear.medal: { slot: stage_clear, theme: medal, obtain: reward }
            bundles:
              brass: { items: [trail.brass, stage_clear.brass] }
            """;

    @Test void bundledCatalogHasAStandardForEverySlot() throws IOException {
        Catalog c=Catalog.load(getClass().getClassLoader(),null);
        assertEquals(List.of("stage_clear","rank_up","room_arrival","record_wall","ranking_row","result_card","room_theme","furniture","pedestal"),c.slots());
        for(String slot:c.slots()) { assertTrue(c.standard(slot).standard()); assertTrue(c.standard(slot).everyone()); }
    }
    @Test void slotsAreDataAndProofsOfSkillAreRefused() throws IOException {
        Catalog c=catalog(SHOP);
        assertTrue(c.slots().contains("trail"),"A slot added by file");
        assertEquals("trail.standard",c.standard("trail").id());
        assertEquals(List.of("trail.brass","stage_clear.brass"),c.bundle("brass").items());
        for(String proof:List.of("name_color","rank","title","trophy")) {
            assertThrows(IOException.class,() -> catalog("slots: ["+proof+"]\n"),proof);
            assertThrows(IOException.class,() -> catalog("items:\n  x: { slot: "+proof+", theme: t, obtain: purchase }\n"),proof);
        }
        assertThrows(IOException.class,() -> catalog("slots: [hat]\n"),"A slot needs a standard item");
        assertThrows(IOException.class,() -> catalog("items:\n  x: { slot: nowhere, theme: t, obtain: purchase }\n"));
        assertThrows(IOException.class,() -> catalog("items:\n  x: { slot: stage_clear, theme: t, obtain: sometimes }\n"));
        assertThrows(IOException.class,() -> catalog(SHOP+"  medal: { items: [stage_clear.medal] }\n"),"Reward items are never sold, not even in a bundle");
    }
    @Test void owningUsingAndBuying() throws Exception {
        try(Database db=Database.open(dir.resolve("g.db"),LOG)) {
            Cosmetics c=new Cosmetics(db,catalog(SHOP)); c.load();
            assertTrue(c.owns(a,"trail.standard"),"Standard items are everyone's");
            assertFalse(c.owns(a,"trail.brass"));
            assertEquals("stage_clear.standard",c.equipped(a,"stage_clear").id());
            assertFalse(c.equip(a,"trail.brass"),"Not owned");
            assertEquals(Cosmetics.Grant.NOT_FOR_SALE,c.grant(a,"stage_clear.medal",Cosmetics.Source.PURCHASE),"Reward-only items cannot be bought");
            assertEquals(Cosmetics.Grant.GRANTED,c.grant(a,"stage_clear.medal",Cosmetics.Source.REWARD));
            assertEquals(Cosmetics.Grant.ALREADY_OWNED,c.grant(a,"stage_clear.medal",Cosmetics.Source.REWARD));
            assertEquals(Cosmetics.Grant.EVERYONE_HAS_IT,c.grant(a,"trail.standard",Cosmetics.Source.REWARD));
            assertThrows(IllegalArgumentException.class,() -> c.purchase(a,a,"stage_clear.medal","manual",null));
            Cosmetics.Purchase p=c.purchase(a,a,"brass","tebex","order-1");
            assertEquals(List.of("trail.brass","stage_clear.brass"),p.items());
            assertTrue(c.owns(a,"trail.brass") && c.owns(a,"stage_clear.brass"),"A bundle gives each item");
            assertTrue(c.equip(a,"stage_clear.brass"));
            assertEquals("stage_clear.brass",c.equipped(a,"stage_clear").id());
            c.purchase(b,a,"trail.brass","manual",null);
            assertTrue(c.revoke(a,"stage_clear.brass"));
            assertEquals("stage_clear.standard",c.equipped(a,"stage_clear").id(),"Taken back: the standard again");
        }
        try(Database db=Database.open(dir.resolve("g.db"),LOG)) {
            Cosmetics c=new Cosmetics(db,catalog(SHOP)); c.load();
            assertTrue(c.owns(a,"trail.brass")); assertFalse(c.owns(a,"stage_clear.brass"));
            assertEquals(Set.of("stage_clear.medal","trail.brass"),new HashSet<>(c.owned(a).stream().map(Cosmetics.Owned::item).toList()));
            List<Cosmetics.Purchase> orders=c.purchases(a);
            assertEquals(2,orders.size(),"Purchases stay even when an item is taken back");
            Cosmetics.Purchase bundle=orders.stream().filter(o -> "brass".equals(o.bundle())).findFirst().orElseThrow();
            assertEquals(Set.of("trail.brass","stage_clear.brass"),new HashSet<>(bundle.items()));
            assertEquals("order-1",bundle.providerOrder()); assertEquals("tebex",bundle.provider());
            Cosmetics.Purchase gift=orders.stream().filter(o -> o.bundle()==null).findFirst().orElseThrow();
            assertEquals(b,gift.buyer()); assertEquals(a,gift.recipient(),"Buyer and recipient are kept apart");
            assertEquals(1,c.purchases(b).size());
        }
    }
}
