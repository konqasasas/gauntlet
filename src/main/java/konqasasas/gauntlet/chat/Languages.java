package konqasasas.gauntlet.chat;

import java.util.*;

/** Translation target languages. Codes are ISO 639-1, with zh-CN / zh-TW for Chinese. */
public final class Languages {
    private static final Map<String,String> LABELS=new LinkedHashMap<>();
    static {
        LABELS.put("ja","日本語"); LABELS.put("en","English"); LABELS.put("ko","한국어");
        LABELS.put("zh-CN","简体中文"); LABELS.put("zh-TW","繁體中文"); LABELS.put("es","Español");
        LABELS.put("pt","Português"); LABELS.put("fr","Français"); LABELS.put("de","Deutsch");
        LABELS.put("ru","Русский"); LABELS.put("it","Italiano"); LABELS.put("th","ไทย");
        LABELS.put("vi","Tiếng Việt"); LABELS.put("id","Bahasa Indonesia");
    }
    public static Set<String> codes() { return Collections.unmodifiableSet(LABELS.keySet()); }
    /** Canonical code for user input such as "JA", "zh-tw", "zh_cn"; null when unsupported. */
    public static String normalize(String input) {
        if(input==null) return null;
        String value=input.strip().replace('_','-');
        for(String code:LABELS.keySet()) if(code.equalsIgnoreCase(value)) return code;
        if(value.equalsIgnoreCase("zh")) return "zh-CN";
        return null;
    }
    /** The client's language setting, or English when it is not a supported target. */
    public static String fromLocale(Locale locale) {
        if(locale==null) return "en";
        String language=locale.getLanguage().toLowerCase(Locale.ROOT);
        if(language.equals("zh")) {
            String country=locale.getCountry().toUpperCase(Locale.ROOT);
            return country.equals("TW") || country.equals("HK") || country.equals("MO") ? "zh-TW" : "zh-CN";
        }
        String code=normalize(language);
        return code==null ? "en" : code;
    }
    public static String label(String code) {
        String base=normalize(code);
        return base==null ? (code==null ? "?" : code) : LABELS.get(base);
    }
    /** "zh-TW" -> "zh"; used to compare a detected language with a target. */
    public static String base(String code) {
        if(code==null) return "";
        int dash=code.indexOf('-');
        return (dash<0 ? code : code.substring(0,dash)).toLowerCase(Locale.ROOT);
    }
    public static boolean sameLanguage(String a,String b) { return !base(a).isEmpty() && base(a).equals(base(b)); }
    private Languages() {}
}
