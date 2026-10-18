package konqasasas.gauntlet.display;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DisplayStyleTest {
    @TempDir Path dir;

    private DisplayStyle bundled() throws IOException { return DisplayStyle.load(getClass().getClassLoader(),dir.resolve("display.yml"),null); }
    private static String plain(Component c) { return PlainTextComponentSerializer.plainText().serialize(c); }
    private static List<String> plain(List<Component> lines) { return lines.stream().map(DisplayStyleTest::plain).toList(); }

    @Test void defaultsKeepTheFormerLook() throws IOException {
        DisplayStyle s=bundled();
        assertEquals("[ 1 ]",s.badgeText(1));
        assertEquals("[11]",s.badgeText(11));
        Component name=s.rank(1,"Steve");
        assertEquals("[ 1 ] [ADMIN] Steve",plain(s.full(s.badge(1),true,name)));
        assertEquals("[ 1 ] Steve",plain(s.full(s.badge(1),false,name)));
        assertEquals("[ 1 ] Steve",plain(s.nametag(s.badge(1),name)));
        assertEquals("[ 1 ] Steve: hi",plain(s.chat(s.full(s.badge(1),false,name),Component.text("hi"))));
        assertEquals("◆ Catena Online ◆",plain(s.tabHeader("Catena Online")));
        assertEquals("◆ Catena Online ◆",plain(s.sidebarTitle("Catena Online")));
        assertEquals(TextColor.fromHexString("#d9d9d9"),colors(name).getFirst());
    }
    @Test void gradientRanksColorEachLetter() throws IOException {
        List<TextColor> colors=colors(bundled().rank(7,"Gradient"));
        assertEquals(8,colors.size());
        assertNotEquals(colors.getFirst(),colors.getLast());
    }
    @Test void namesAreNeverTags() throws IOException {
        DisplayStyle s=bundled();
        assertEquals("<red>x</red>",plain(s.rank(1,"<red>x</red>")));
        assertEquals("<b>",plain(s.tabHeader("<b>")).replace("◆ ","").replace(" ◆",""));
    }
    @Test void pingRanges() throws IOException {
        DisplayStyle s=bundled();
        assertEquals("50ms",plain(s.ping(50,100,200)));
        assertEquals(NamedTextColor.GREEN,colors(s.ping(50,100,200)).getFirst());
        assertEquals(NamedTextColor.YELLOW,colors(s.ping(150,100,200)).getFirst());
        assertEquals(NamedTextColor.RED,colors(s.ping(200,100,200)).getFirst());
    }
    @Test void sidebarInsertsTheCourseOnlyDuringACourse() throws IOException {
        DisplayStyle s=bundled();
        Component badge=s.badge(1);
        List<String> outside=plain(s.sidebar(new DisplayStyle.SidebarValues("Steve",badge,"1.5h","mc.catenaonline.net",null)));
        assertEquals(List.of("","Player:"," Steve","Rank:"," [ 1 ]","Playtime:"," 1.5h","","mc.catenaonline.net"),outside);
        List<String> inside=plain(s.sidebar(new DisplayStyle.SidebarValues("Steve",badge,"1.5h","mc.catenaonline.net",
                new DisplayStyle.CourseValues("Lava Stairs","87%",3))));
        assertEquals(List.of("","Player:"," Steve","Rank:"," [ 1 ]","Playtime:"," 1.5h","",
                "───────────","Course:"," Lava Stairs","Best: 87%","Attempts: 3","","mc.catenaonline.net"),inside);
    }
    @Test void serverListDescription() throws IOException {
        assertEquals("◆ Catena Online ◆\nBlock-placing parkour | Java 26.3",plain(bundled().motd("Catena Online",3,20)));
        Files.writeString(dir.resolve("display.yml"),"motd:\n  - \"<online>/<max> <server>\"\n");
        assertEquals("3/20 X",plain(bundled().motd("X",3,20)));
        Files.writeString(dir.resolve("display.yml"),"motd: []\n");
        assertNull(bundled().motd("X",3,20),"An empty motd keeps server.properties");
        Files.writeString(dir.resolve("display.yml"),"motd:\n  - a\n  - b\n  - c\n");
        IOException e=assertThrows(IOException.class,this::bundled);
        assertTrue(e.getMessage().contains("motd"),e.getMessage());
    }
    @Test void overrideReplacesKeysAndIgnoresUnknownOnes() throws IOException {
        Files.writeString(dir.resolve("display.yml"),"names:\n  full: \"<name> <badge>\"\nranks:\n  badge: \"R<rank>\"\nno.such: x\n");
        DisplayStyle s=bundled();
        assertEquals("Steve R1",plain(s.full(s.badge(1),true,s.rank(1,"Steve"))),"A format without <admin> leaves the tag out");
        assertEquals("◆ X ◆",plain(s.tabHeader("X")),"Other keys keep the bundled text");
    }
    @Test void invalidRankStylesAreRejected() throws IOException {
        Files.writeString(dir.resolve("display.yml"),"ranks:\n  styles:\n    - \"<red><text>\"\n");
        IOException e=assertThrows(IOException.class,this::bundled);
        assertTrue(e.getMessage().contains("needs "+DisplayStyle.RANKS),e.getMessage());
        StringBuilder noText=new StringBuilder("ranks:\n  styles:\n");
        for(int i=0;i<DisplayStyle.RANKS;i++) noText.append("    - \"<red>").append(i==4 ? "" : "<text>").append("\"\n");
        Files.writeString(dir.resolve("display.yml"),noText);
        e=assertThrows(IOException.class,this::bundled);
        assertTrue(e.getMessage().contains("entry 5"),e.getMessage());
        Files.writeString(dir.resolve("display.yml"),"names: [unclosed\n");
        assertThrows(IOException.class,this::bundled);
    }

    /** The color of every letter, in order. */
    private static List<TextColor> colors(Component component) {
        List<TextColor> out=new ArrayList<>();
        collect(component,null,out);
        return out;
    }
    private static void collect(Component c,TextColor inherited,List<TextColor> out) {
        TextColor color=c.color()!=null ? c.color() : inherited;
        if(c instanceof TextComponent text) for(int i=0;i<text.content().codePointCount(0,text.content().length());i++) out.add(color);
        for(Component child:c.children()) collect(child,color,out);
    }
}
