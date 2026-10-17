package konqasasas.gauntlet.ui;

import java.util.*;

/**
 * The payload of a Dialog custom click: the editor writes JSON, the client
 * sends it as SNBT ({key:"ui_lang",delta:-10,on:1b}). Top-level values are
 * read as text (numbers without their type suffix, 1b/0b as true/false);
 * nested compounds and lists are skipped. A JSON null is not sent at all, so a
 * missing key reads as null. Parsed by hand: the server has no adventure-nbt.
 */
public final class DialogPayload {
    private final Map<String,String> values;
    private DialogPayload(Map<String,String> values) { this.values=values; }

    public static final DialogPayload EMPTY=new DialogPayload(Map.of());

    /** Unreadable or empty SNBT reads as EMPTY. */
    public static DialogPayload parse(String snbt) {
        if(snbt==null || snbt.isBlank()) return EMPTY;
        try { return new DialogPayload(Map.copyOf(new Reader(snbt.strip()).compound())); }
        catch(IllegalArgumentException e) { return EMPTY; }
    }
    public String get(String key) { return values.get(key); }
    public int integer(String key,int fallback) {
        String value=values.get(key); if(value==null) return fallback;
        try { return Integer.parseInt(value); } catch(NumberFormatException e) { return fallback; }
    }
    @Override public String toString() { return values.toString(); }

    private static final class Reader {
        private final String s; private int i;
        Reader(String s) { this.s=s; }

        Map<String,String> compound() {
            Map<String,String> out=new HashMap<>();
            expect('{'); space();
            if(peek()=='}') { i++; return end(out); }
            while(true) {
                space(); String key=peek()=='"' || peek()=='\'' ? quoted() : bare();
                space(); expect(':'); space();
                String value=value();
                if(value!=null) out.put(key,value);
                space();
                if(peek()==',') { i++; continue; }
                expect('}'); return end(out);
            }
        }
        private Map<String,String> end(Map<String,String> out) {
            space(); if(i!=s.length()) throw new IllegalArgumentException("trailing text"); return out;
        }
        /** A scalar as text, or null for a skipped compound/list. */
        private String value() {
            char c=peek();
            if(c=='"' || c=='\'') return quoted();
            if(c=='{' || c=='[') { skipNested(); return null; }
            String raw=bare();
            if(raw.equals("true") || raw.equals("false")) return raw;
            return number(raw);
        }
        private static String number(String raw) {
            char suffix=Character.toLowerCase(raw.charAt(raw.length()-1));
            String digits="bslfd".indexOf(suffix)>=0 ? raw.substring(0,raw.length()-1) : raw;
            try {
                if(suffix=='b' && (digits.equals("1") || digits.equals("0"))) return digits.equals("1") ? "true" : "false";
                double value=Double.parseDouble(digits);
                return value==Math.rint(value) && !digits.contains("e") && !digits.contains("E") ? String.valueOf((long)value) : String.valueOf(value);
            } catch(NumberFormatException e) { return raw; }
        }
        private String quoted() {
            char quote=s.charAt(i++); StringBuilder out=new StringBuilder();
            while(i<s.length()) {
                char c=s.charAt(i++);
                if(c==quote) return out.toString();
                if(c=='\\' && i<s.length()) c=s.charAt(i++);
                out.append(c);
            }
            throw new IllegalArgumentException("unterminated string");
        }
        private String bare() {
            int start=i;
            while(i<s.length() && (Character.isLetterOrDigit(s.charAt(i)) || "._+-".indexOf(s.charAt(i))>=0)) i++;
            if(start==i) throw new IllegalArgumentException("expected a value at "+i);
            return s.substring(start,i);
        }
        private void skipNested() {
            int depth=0;
            do {
                char c=s.charAt(i);
                if(c=='"' || c=='\'') { quoted(); continue; }
                if(c=='{' || c=='[') depth++;
                else if(c=='}' || c==']') depth--;
                i++;
            } while(depth>0 && i<s.length());
            if(depth!=0) throw new IllegalArgumentException("unbalanced");
        }
        private char peek() { if(i>=s.length()) throw new IllegalArgumentException("unexpected end"); return s.charAt(i); }
        private void expect(char c) { if(peek()!=c) throw new IllegalArgumentException("expected "+c+" at "+i); i++; }
        private void space() { while(i<s.length() && Character.isWhitespace(s.charAt(i))) i++; }
    }
}
