package konqasasas.gauntlet.i18n;

import java.util.Locale;

/** Interface languages (separate from the chat translation targets). */
public final class Lang {
    /** The client's language when it is Japanese, otherwise English. */
    public static String fromLocale(Locale locale) {
        return locale!=null && locale.getLanguage().equalsIgnoreCase("ja") ? "ja" : "en";
    }
    public static boolean supported(String code) { return Messages.LANGUAGES.contains(code); }
    private Lang() {}
}
