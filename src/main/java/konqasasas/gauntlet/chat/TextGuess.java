package konqasasas.gauntlet.chat;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Cheap local checks that save API calls on free quotas. */
public final class TextGuess {
    /**
     * True when the text clearly needs no translation into target: too short,
     * no letters, or obviously written in the target language already.
     */
    public static boolean skip(String text,String target,String senderLanguage) {
        if(text==null) return true;
        int letters=0,kana=0,hangul=0,han=0,latin=0,other=0;
        for(int i=0;i<text.length();) {
            int c=text.codePointAt(i); i+=Character.charCount(c);
            if(!Character.isLetter(c)) continue;
            letters++;
            Character.UnicodeScript script=Character.UnicodeScript.of(c);
            switch(script) {
                case HIRAGANA,KATAKANA -> kana++;
                case HANGUL -> hangul++;
                case HAN -> han++;
                case LATIN -> latin++;
                default -> other++;
            }
        }
        if(letters<2) return true;
        String base=Languages.base(target);
        if(base.equals("ja") && kana>0) return true;
        if(base.equals("ko") && hangul>0 && kana==0) return true;
        if(base.equals("zh") && han>0 && kana==0 && hangul==0 && latin==0) return true;
        // An English client writing plain ASCII to an English reader.
        if(base.equals("en") && latin==letters && Languages.sameLanguage(senderLanguage,"en")) return true;
        return false;
    }
    /** Splits text into pieces of at most maxBytes UTF-8 bytes, preferring sentence and word breaks. */
    public static List<String> chunks(String text,int maxBytes) {
        List<String> out=new ArrayList<>();
        String rest=text;
        while(utf8(rest)>maxBytes) {
            int end=0,bytes=0,lastBreak=-1;
            while(end<rest.length()) {
                int c=rest.codePointAt(end); int size=utf8(new String(Character.toChars(c)));
                if(bytes+size>maxBytes) break;
                bytes+=size; end+=Character.charCount(c);
                if(Character.isWhitespace(c) || "。．.!！?？、,，".indexOf(c)>=0) lastBreak=end;
            }
            int cut=lastBreak>0 ? lastBreak : end;
            out.add(rest.substring(0,cut)); rest=rest.substring(cut);
        }
        if(!rest.isEmpty()) out.add(rest);
        return out;
    }
    private static int utf8(String s) { return s.getBytes(StandardCharsets.UTF_8).length; }
    /** Decodes the HTML entities some engines leave in plain-text results. */
    public static String unescapeHtml(String s) {
        if(s==null || s.indexOf('&')<0) return s;
        StringBuilder out=new StringBuilder(s.length());
        for(int i=0;i<s.length();i++) {
            char c=s.charAt(i); int semi=c=='&' ? s.indexOf(';',i) : -1;
            if(semi<0 || semi-i>10) { out.append(c); continue; }
            String entity=s.substring(i+1,semi); String decoded=switch(entity) {
                case "amp" -> "&"; case "lt" -> "<"; case "gt" -> ">"; case "quot" -> "\""; case "apos" -> "'"; case "nbsp" -> " ";
                default -> {
                    try {
                        if(entity.startsWith("#x") || entity.startsWith("#X")) yield new String(Character.toChars(Integer.parseInt(entity.substring(2),16)));
                        if(entity.startsWith("#")) yield new String(Character.toChars(Integer.parseInt(entity.substring(1))));
                    } catch(IllegalArgumentException e) { yield null; }
                    yield null;
                }
            };
            if(decoded==null) out.append(c); else { out.append(decoded); i=semi; }
        }
        return out.toString();
    }
    private TextGuess() {}
}
