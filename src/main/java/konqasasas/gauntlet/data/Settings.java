package konqasasas.gauntlet.data;

import java.util.*;
import konqasasas.gauntlet.chat.Languages;

/**
 * Every personal setting. A settings screen lists ALL where menu() is true;
 * names and descriptions are the message keys "setting.<key>.name/.desc".
 */
public final class Settings {
    /** Interface language. auto = the client's language (Japanese, otherwise English). */
    public static final Setting<String> UI_LANG=Setting.of("ui_lang",String.class,"auto",List.of("auto","ja","en"),true);
    /** Chat translation target. ui = same as the interface language. */
    public static final Setting<String> TRANSLATE_LANG=Setting.of("translate_lang",String.class,"ui",Setting.concat("ui",Languages.codes()),true);
    public static final Setting<Boolean> TRANSLATE_AUTO=Setting.of("translate_auto",Boolean.class,false,List.of(false,true),true);
    /** The "63% (Best 87%)" title after a failure. */
    public static final Setting<Boolean> FAIL_TITLE=Setting.of("fail.title",Boolean.class,true,List.of(true,false),true);
    /** Plugin cues: checkpoint, clear, failure... (percent). */
    public static final Setting<Integer> SOUND_SYSTEM=Setting.of("sound.system",Integer.class,100,Setting.percent(),true);
    /** Sounds played by course triggers (percent). */
    public static final Setting<Integer> SOUND_COURSE=Setting.of("sound.course",Integer.class,100,Setting.percent(),true);
    /** Other players shown; changed by the hotbar item, not the menu. */
    public static final Setting<Boolean> PLAYERS_VISIBLE=Setting.of("players.visible",Boolean.class,true,List.of(true,false),false);

    public static final List<Setting<?>> ALL=List.of(UI_LANG,TRANSLATE_LANG,TRANSLATE_AUTO,FAIL_TITLE,SOUND_SYSTEM,SOUND_COURSE,PLAYERS_VISIBLE);
    public static Setting<?> byKey(String key) { return ALL.stream().filter(s -> s.key().equalsIgnoreCase(key)).findFirst().orElse(null); }
    private Settings() {}
}
