package konqasasas.gauntlet.ui;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class DialogPayloadTest {
    @Test void readsTextNumbersAndBooleans() {
        DialogPayload p=DialogPayload.parse("{key:\"sound.system\",delta:-10,value:70,on:1b,off:0b}");
        assertEquals("sound.system",p.get("key"));
        assertEquals(-10,p.integer("delta",0));
        assertEquals("70",p.get("value"));
        assertEquals("true",p.get("on"));
        assertEquals("false",p.get("off"));
    }
    @Test void wholeFloatsReadAsIntegers() {
        assertEquals("10",DialogPayload.parse("{value:10.0d}").get("value"));
        assertEquals("0.5",DialogPayload.parse("{value:0.5f}").get("value"));
    }
    @Test void missingAndBrokenReadAsAbsent() {
        assertNull(DialogPayload.parse("{key:\"translate_lang\"}").get("value"));
        assertNull(DialogPayload.parse("{}").get("key"));
        assertNull(DialogPayload.parse(null).get("key"));
        assertNull(DialogPayload.parse("not snbt").get("key"));
        assertEquals(3,DialogPayload.parse("{delta:\"x\"}").integer("delta",3));
    }
    @Test void readsWhatTheClientSent() {
        // As logged by Paper 26.3 for the close button.
        DialogPayload p=DialogPayload.parse("{element:\"close_btn_hs\",screen:\"top_menu\"}");
        assertEquals("top_menu",p.get("screen"));
        assertEquals("close_btn_hs",p.get("element"));
    }
    @Test void readsQuotedKeysAndEscapes() {
        DialogPayload p=DialogPayload.parse("{ \"model\" : 'social_emotes', id:\"say \\\"hi\\\"\", n:3L }");
        assertEquals("social_emotes",p.get("model"));
        assertEquals("say \"hi\"",p.get("id"));
        assertEquals("3",p.get("n"));
    }
    @Test void nestedValuesAreIgnored() {
        DialogPayload p=DialogPayload.parse("{id:\"cheer\",extra:{a:1}}");
        assertEquals("cheer",p.get("id"));
        assertNull(p.get("extra"));
    }
}
