package konqasasas.gauntlet.data;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SettingEditsTest {
    @Test void setParsesChoicesOnly() {
        assertEquals("ja",SettingEdits.set(Settings.UI_LANG,"ja"));
        assertNull(SettingEdits.set(Settings.UI_LANG,"fr"));
        assertEquals(70,SettingEdits.set(Settings.SOUND_SYSTEM,"70"));
        assertNull(SettingEdits.set(Settings.SOUND_SYSTEM,"75"));
    }
    @Test void nullTranslationTargetMeansInterfaceLanguage() {
        assertEquals("ui",SettingEdits.set(Settings.TRANSLATE_LANG,null));
        assertEquals("zh-CN",SettingEdits.set(Settings.TRANSLATE_LANG,"zh-CN"));
        assertNull(SettingEdits.set(Settings.UI_LANG,null));
    }
    @Test void toggleFlipsBooleansOnly() {
        assertEquals(true,SettingEdits.toggle(Settings.TRANSLATE_AUTO,false));
        assertEquals(false,SettingEdits.toggle(Settings.TRANSLATE_AUTO,true));
        assertNull(SettingEdits.toggle(Settings.SOUND_SYSTEM,100));
    }
    @Test void stepStopsAtTheEnds() {
        assertEquals(90,SettingEdits.step(Settings.SOUND_SYSTEM,100,-10));
        assertEquals(100,SettingEdits.step(Settings.SOUND_SYSTEM,100,10));
        assertEquals(0,SettingEdits.step(Settings.SOUND_COURSE,0,-10));
        assertEquals(10,SettingEdits.step(Settings.SOUND_COURSE,0,10));
        assertNull(SettingEdits.step(Settings.SOUND_COURSE,50,0));
        assertNull(SettingEdits.step(Settings.TRANSLATE_AUTO,false,10));
    }
}
