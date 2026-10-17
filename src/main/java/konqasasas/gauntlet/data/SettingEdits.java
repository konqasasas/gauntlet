package konqasasas.gauntlet.data;

import java.util.*;

/**
 * The new value for a settings screen click (dcg:settings_set / _toggle / _step).
 * Returns null when the click does not apply to the setting.
 */
public final class SettingEdits {
    /** Pick a value. The screen sends null for "same as the interface language". */
    public static <T> T set(Setting<T> setting,String raw) {
        if(raw==null && setting==Settings.TRANSLATE_LANG) raw="ui";
        return setting.parse(raw);
    }
    /** Flip an on/off setting. */
    public static <T> T toggle(Setting<T> setting,T current) {
        if(setting.type()!=Boolean.class) return null;
        return setting.type().cast(!(Boolean)current);
    }
    /** Move a number by delta, stopping at the lowest/highest choice. */
    public static <T> T step(Setting<T> setting,T current,int delta) {
        if(setting.type()!=Integer.class || delta==0) return null;
        List<Integer> choices=setting.choices().stream().map(Integer.class::cast).sorted().toList();
        int target=Math.clamp((long)(Integer)current+delta,choices.getFirst(),choices.getLast());
        // The nearest choice in the direction of the step (choices are 10% apart).
        Integer value=delta>0 ? choices.stream().filter(c -> c>=target).findFirst().orElse(choices.getLast())
                : choices.reversed().stream().filter(c -> c<=target).findFirst().orElse(choices.getFirst());
        return setting.type().cast(value);
    }
    private SettingEdits() {}
}
