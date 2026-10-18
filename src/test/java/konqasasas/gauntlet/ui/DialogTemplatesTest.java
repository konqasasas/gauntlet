package konqasasas.gauntlet.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DialogTemplatesTest {
    private static final String DIALOG="""
        {"type":"minecraft:dialog_list","body":[{"type":"minecraft:plain_message","width":461,"contents":{"text":"","extra":[
          {"text":"A","font":"dcg:space","click_event":{"action":"custom","id":"dcg:settings_set","payload":{"key":"ui_lang","value":"ja"}}},
          {"text":"on","insertion":"dcg:state:translate_auto=on"},
          {"text":"off","insertion":"dcg:state:translate_auto=off"},
          {"text":"not-ja","insertion":"dcg:state:ui_lang!=ja"},
          {"text":"other","insertion":"chat:insert"},
          {"text":"\\n"}]}}]}""";

    private static List<String> texts(JsonObject dialog) {
        List<String> out=new ArrayList<>();
        for(JsonElement e:dialog.getAsJsonArray("body").get(0).getAsJsonObject().getAsJsonObject("contents").getAsJsonArray("extra")) out.add(e.getAsJsonObject().get("text").getAsString());
        return out;
    }
    private static JsonObject parse() { return JsonParser.parseString(DIALOG).getAsJsonObject(); }

    @Test void keepsOnlyMatchingStates() {
        JsonObject out=DialogTemplates.select(parse(),"dcg",Map.of("translate_auto","on","ui_lang","en"));
        assertEquals(List.of("A","on","not-ja","other","\n"),texts(out));
        JsonObject ja=DialogTemplates.select(parse(),"dcg",Map.of("translate_auto","off","ui_lang","ja"));
        assertEquals(List.of("A","off","other","\n"),texts(ja));
    }
    @Test void removesOnlyStateMarkers() {
        JsonArray extra=DialogTemplates.select(parse(),"dcg",Map.of("translate_auto","on")).getAsJsonArray("body").get(0).getAsJsonObject().getAsJsonObject("contents").getAsJsonArray("extra");
        for(JsonElement e:extra) {
            JsonElement insertion=e.getAsJsonObject().get("insertion");
            assertTrue(insertion==null || !insertion.getAsString().startsWith("dcg:state:"));
        }
        assertEquals("chat:insert",extra.get(3).getAsJsonObject().get("insertion").getAsString());
    }
    @Test void templateIsNotChanged() {
        JsonObject template=parse();
        DialogTemplates.select(template,"dcg",Map.of());
        assertEquals(6,template.getAsJsonArray("body").get(0).getAsJsonObject().getAsJsonObject("contents").getAsJsonArray("extra").size());
    }
    @Test void customPayloadBecomesSnbt() {
        JsonObject click=DialogTemplates.select(parse(),"dcg",Map.of()).getAsJsonArray("body").get(0).getAsJsonObject().getAsJsonObject("contents").getAsJsonArray("extra").get(0).getAsJsonObject().getAsJsonObject("click_event");
        assertEquals("{\"key\":\"ui_lang\",\"value\":\"ja\"}",click.get("payload").getAsString());
        // What the plugin receives back reads the same.
        assertEquals("ja",DialogPayload.parse(click.get("payload").getAsString()).get("value"));
    }
    @Test void adventureReadsTheSelectedJson() {
        // The parts the editor writes: fonts, shadow colour, a custom click with payload, a tooltip.
        String json="""
            {"text":"","extra":[
              {"text":"x","font":"dcg:space","click_event":{"action":"custom","id":"dcg:settings_set","payload":{"key":"sound.system","delta":-10}},"hover_event":{"action":"show_text","value":{"text":"tip"}}},
              {"text":"y","font":"dcg:text","color":"#ffffff","shadow_color":-12632257,"bold":true,"insertion":"dcg:state:translate_auto=on"}]}""";
        JsonObject dialog=new JsonObject();
        JsonArray body=new JsonArray(); JsonObject message=new JsonObject();
        message.add("contents",JsonParser.parseString(json)); body.add(message); dialog.add("body",body);
        JsonElement contents=DialogTemplates.select(dialog,"dcg",Map.of("translate_auto","on")).getAsJsonArray("body").get(0).getAsJsonObject().get("contents");
        var component=net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().deserializeFromTree(contents);
        var first=component.children().get(0);
        var click=first.style().clickEvent();
        assertNotNull(click);
        assertEquals("dcg:settings_set",((net.kyori.adventure.text.event.ClickEvent.Payload.Custom)click.payload()).key().asString());
        String nbt=((net.kyori.adventure.text.event.ClickEvent.Payload.Custom)click.payload()).nbt().string();
        assertEquals(-10,DialogPayload.parse(nbt).integer("delta",0));
        assertNotNull(first.style().hoverEvent());
        var second=component.children().get(1);
        assertEquals("dcg:text",second.style().font().asString());
        assertNotNull(second.style().shadowColor());
        assertNull(second.style().insertion());
    }
    @Test void snbtValues() {
        assertEquals("{\"n\":3,\"d\":0.5d,\"b\":true,\"s\":\"a\\\"b\",\"l\":[1,2]}",DialogTemplates.snbt(JsonParser.parseString("{\"n\":3,\"d\":0.5,\"b\":true,\"s\":\"a\\\"b\",\"x\":null,\"l\":[1,null,2]}")));
    }
    @Test void conditions() {
        assertTrue(DialogTemplates.matches("sound.system=70",Map.of("sound.system","70")));
        assertFalse(DialogTemplates.matches("sound.system=70",Map.of()));
        assertTrue(DialogTemplates.matches("ui_lang!=ja",Map.of()));
        assertFalse(DialogTemplates.matches("bad condition",Map.of()));
        // & でつないだ条件はすべて満たすときだけ
        assertTrue(DialogTemplates.matches("tab=sound&muted!=on",Map.of("tab","sound")));
        assertFalse(DialogTemplates.matches("tab=sound&muted!=on",Map.of("tab","sound","muted","on")));
        assertFalse(DialogTemplates.matches("tab=sound&",Map.of("tab","sound")));
    }
    @Test void loadsFilesByName(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.json"),DIALOG);
        DialogTemplates t=DialogTemplates.load("dcg",dir);
        assertEquals(Set.of("settings"),t.screens());
        assertNotNull(t.select("settings",Map.of()));
        assertNull(t.select("missing",Map.of()));
        assertTrue(DialogTemplates.load("dcg",dir.resolve("none")).screens().isEmpty());
    }
    @Test void manifestGivesDefaultsAndIsNotAScreen(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.json"),DIALOG);
        Files.writeString(dir.resolve("manifest.json"),"""
            {"format":"dialog-custom-gui/manifest","screens":[
              {"id":"settings","states":{"keys":{},"defaults":{"translate_auto":"off","ui_lang":"ja","lang":"ja"}}},
              {"id":"no_states"}]}""");
        DialogTemplates t=DialogTemplates.load("dcg",dir);
        assertEquals(Set.of("settings"),t.screens(),"manifest.json is not a screen");
        assertEquals(Map.of("translate_auto","off","ui_lang","ja","lang","ja"),t.defaults("settings"));
        assertEquals(Map.of(),t.defaults("no_states"));
        assertEquals(Map.of(),t.defaults("missing"));
        // A state the player has no value for falls back to the default; the player's values win.
        assertEquals(List.of("A","off","other","\n"),texts(t.select("settings",Map.of())));
        assertEquals(List.of("A","on","not-ja","other","\n"),texts(t.select("settings",Map.of("translate_auto","on","ui_lang","en"))));
    }
}
