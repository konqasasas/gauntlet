package konqasasas.gauntlet.display;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.regex.*;
import konqasasas.gauntlet.i18n.Messages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The web editor (web/editor/index.html) embeds the bundled defaults; they must not drift from the plugin's files. */
class DisplayEditorDefaultsTest {
    private static final Path EDITOR=Path.of("web","editor","index.html");
    @TempDir Path dir;

    private static String block(String html,String id) {
        Matcher m=Pattern.compile("<script type=\"[^\"]+\" id=\""+id+"\">\\n(.*?)\\n</script>",Pattern.DOTALL).matcher(html);
        assertTrue(m.find(),"No "+id+" block in "+EDITOR);
        return m.group(1);
    }
    private static String normalize(String s) { return s.replace("\r\n","\n").strip(); }

    @Test void displayDefaultsMatchTheBundledFile() throws IOException {
        String html=Files.readString(EDITOR,StandardCharsets.UTF_8).replace("\r\n","\n");
        String bundled;
        try(InputStream in=getClass().getClassLoader().getResourceAsStream("display.yml")) { bundled=new String(in.readAllBytes(),StandardCharsets.UTF_8); }
        assertEquals(normalize(bundled),normalize(block(html,"default-display")),"Copy src/main/resources/display.yml into the editor");
    }
    @Test void languageDefaultsMatchTheBundledFiles() throws IOException {
        String html=Files.readString(EDITOR,StandardCharsets.UTF_8).replace("\r\n","\n");
        JsonObject langs=JsonParser.parseString(block(html,"default-lang")).getAsJsonObject();
        Messages messages=Messages.load(getClass().getClassLoader(),dir,null);
        for(String lang:Messages.LANGUAGES) {
            JsonObject keys=langs.getAsJsonObject(lang);
            assertNotNull(keys,"No "+lang+" in the editor");
            for(var entry:keys.entrySet())
                assertEquals(messages.template(lang,entry.getKey()),entry.getValue().getAsString(),lang+" "+entry.getKey());
        }
    }
}
