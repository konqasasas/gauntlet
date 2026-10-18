package konqasasas.gauntlet.i18n;

import static org.junit.jupiter.api.Assertions.*;
import java.io.StringReader;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import konqasasas.gauntlet.data.*;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessagesTest {
    @TempDir Path dir;
    /** MiniMessage tag names that are styles, not arguments. */
    private static final Set<String> STYLES=Set.of("black","dark_blue","dark_green","dark_aqua","dark_red","dark_purple","gold","gray","grey",
            "dark_gray","dark_grey","blue","green","aqua","red","light_purple","yellow","white","color","bold","b","italic","i","em",
            "underlined","u","strikethrough","st","obfuscated","obf","gradient","rainbow","key","reset","newline","br");
    private static final Pattern TAG=Pattern.compile("<([a-z_]+)(?=[:>])");

    private Messages bundled() throws Exception { return Messages.load(getClass().getClassLoader(),dir,null); }

    @Test void englishHasEveryPlayerFacingKeyWithTheSameArguments() throws Exception {
        Messages m=bundled();
        Set<String> ja=m.keys("ja"),en=m.keys("en");
        Set<String> missing=new TreeSet<>(ja); missing.removeAll(en); missing.removeIf(k -> k.startsWith("editor."));
        assertEquals(Set.of(),missing,"Keys only in Japanese (editor.* may be)");
        Set<String> extra=new TreeSet<>(en); extra.removeAll(ja);
        assertEquals(Set.of(),extra,"Keys only in English");
        for(String key:en) assertEquals(arguments(m.template("ja",key)),arguments(m.template("en",key)),"Arguments differ: "+key);
    }
    @Test void everyTagIsUnderstood() throws Exception {
        Messages m=bundled();
        MiniMessage mini=MiniMessage.miniMessage();
        for(String lang:Messages.LANGUAGES) for(String key:m.keys(lang)) {
            String template=m.template(lang,key);
            TagResolver args=TagResolver.resolver(arguments(template).stream().map(n -> (TagResolver)Placeholder.unparsed(n,"x")).toList());
            for(String line:template.split("\n",-1)) {
                String plain=PlainTextComponentSerializer.plainText().serialize(mini.deserialize(line,args));
                assertFalse(plain.contains("<") || plain.contains(">"),"Unparsed tag in "+lang+" "+key+": "+plain);
            }
        }
    }
    @Test void settingsHaveNamesAndDescriptions() throws Exception {
        Messages m=bundled();
        for(Setting<?> s:Settings.ALL) for(String suffix:List.of(".name",".desc")) for(String lang:Messages.LANGUAGES)
            assertTrue(m.keys(lang).contains("setting."+s.key()+suffix),lang+" setting."+s.key()+suffix);
    }
    @Test void overridesAndFallbacks() throws Exception {
        Files.writeString(dir.resolve("en.yml"),"prefix: \"[X] \"\nonly.override: \"o\"\n");
        Messages m=Messages.load(getClass().getClassLoader(),dir,null);
        assertEquals("[X] ",m.template("en","prefix"));
        assertEquals("<color:#e2c27a>[Gauntlet]</color> ",m.template("ja","prefix"),"Other languages keep the bundled text");
        assertEquals("o",m.template("ja","only.override"),"Japanese falls back to English");
        assertEquals(m.template("ja","editor.admin-only"),m.template("en","editor.admin-only"),"Editor texts fall back to Japanese");
        assertNull(m.template("en","no.such.key"));
    }
    @Test void nestedYamlAndListsFlatten() {
        Map<String,String> parsed=Messages.parse(new StringReader("a:\n  b: x\n  c:\n    - one\n    - two\nflat.key: 3\n"));
        assertEquals(Map.of("a.b","x","a.c","one\ntwo","flat.key","3"),parsed);
    }
    private static Set<String> arguments(String template) {
        Set<String> names=new TreeSet<>();
        Matcher matcher=TAG.matcher(template);
        while(matcher.find()) if(!STYLES.contains(matcher.group(1))) names.add(matcher.group(1));
        return names;
    }
}
