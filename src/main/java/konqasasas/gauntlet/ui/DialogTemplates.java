package konqasasas.gauntlet.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import com.google.gson.*;

/**
 * Screens exported by the Dialog editor as templates/&lt;screen&gt;.json: the
 * Dialog JSON with every state drawn. A state's parts carry the insertion
 * "&lt;namespace&gt;:state:&lt;condition&gt;" (key=value or key!=value); each part draws
 * and steps back to where it started, so dropping the ones that do not match
 * keeps every body line its width. See the editor's lib/states.mjs.
 */
public final class DialogTemplates {
    private static final Pattern CONDITION=Pattern.compile("([a-z0-9_.]{1,32})(!?=)([A-Za-z0-9_.-]{1,32})");
    private final String namespace;
    private final Map<String,JsonObject> screens;

    private DialogTemplates(String namespace,Map<String,JsonObject> screens) { this.namespace=namespace; this.screens=Map.copyOf(screens); }
    public static DialogTemplates empty(String namespace) { return new DialogTemplates(namespace,Map.of()); }

    /** Every *.json in dir, by file name. A missing dir reads as no templates. */
    public static DialogTemplates load(String namespace,Path dir) throws IOException {
        Map<String,JsonObject> screens=new HashMap<>();
        if(Files.isDirectory(dir)) try(DirectoryStream<Path> files=Files.newDirectoryStream(dir,"*.json")) {
            for(Path file:files) {
                String name=file.getFileName().toString();
                try { screens.put(name.substring(0,name.length()-5),JsonParser.parseString(Files.readString(file,StandardCharsets.UTF_8)).getAsJsonObject()); }
                catch(JsonParseException|IllegalStateException e) { throw new IOException("Unreadable menu template "+file+": "+e.getMessage(),e); }
            }
        }
        return new DialogTemplates(namespace,screens);
    }
    public Set<String> screens() { return screens.keySet(); }
    public boolean has(String screen) { return screens.containsKey(screen); }

    /** The screen with only the states matching values, ready for the component serializer; null if there is no template. */
    public JsonObject select(String screen,Map<String,String> values) {
        JsonObject template=screens.get(screen);
        return template==null ? null : select(template,namespace,values);
    }

    static JsonObject select(JsonObject dialog,String namespace,Map<String,String> values) {
        JsonObject out=dialog.deepCopy();
        String prefix=namespace+":state:";
        JsonArray body=out.has("body") && out.get("body").isJsonArray() ? out.getAsJsonArray("body") : new JsonArray();
        for(JsonElement element:body) {
            if(!element.isJsonObject()) continue;
            JsonObject contents=element.getAsJsonObject().getAsJsonObject("contents");
            if(contents==null || !contents.has("extra")) continue;
            JsonArray kept=new JsonArray();
            for(JsonElement part:contents.getAsJsonArray("extra")) {
                if(part.isJsonObject()) {
                    JsonObject p=part.getAsJsonObject();
                    JsonElement insertion=p.get("insertion");
                    if(insertion!=null && insertion.isJsonPrimitive() && insertion.getAsString().startsWith(prefix)) {
                        if(!matches(insertion.getAsString().substring(prefix.length()),values)) continue;
                        p.remove("insertion");
                    }
                    payloadAsSnbt(p);
                }
                kept.add(part);
            }
            contents.add("extra",kept);
        }
        return out;
    }

    /**
     * key=value: the value is exactly that; key!=value: anything else, a missing value too.
     * Conditions joined with &amp; must all hold (a group's state and its members' states).
     */
    static boolean matches(String condition,Map<String,String> values) {
        String[] parts=condition.split("&",-1);
        for(String part:parts) {
            var m=CONDITION.matcher(part);
            if(!m.matches()) return false;
            boolean equal=m.group(3).equals(values.get(m.group(1)));
            if(m.group(2).equals("=")!=equal) return false;
        }
        return true;
    }

    // Adventure reads a custom click's payload only as SNBT text, while the editor writes it as JSON.
    private static void payloadAsSnbt(JsonObject part) {
        JsonObject click=part.getAsJsonObject("click_event");
        if(click==null || !"custom".equals(stringOr(click.get("action"))) || !click.has("payload")) return;
        JsonElement payload=click.get("payload");
        if(payload.isJsonObject() || payload.isJsonArray()) click.addProperty("payload",snbt(payload));
    }
    private static String stringOr(JsonElement e) { return e!=null && e.isJsonPrimitive() ? e.getAsString() : null; }

    /** JSON as SNBT: keys and strings quoted, nulls left out, true/false as SNBT booleans. */
    static String snbt(JsonElement e) {
        if(e==null || e.isJsonNull()) return "{}";
        if(e.isJsonObject()) {
            StringJoiner out=new StringJoiner(",","{","}");
            for(var entry:e.getAsJsonObject().entrySet()) if(!entry.getValue().isJsonNull()) out.add(quote(entry.getKey())+":"+snbt(entry.getValue()));
            return out.toString();
        }
        if(e.isJsonArray()) {
            StringJoiner out=new StringJoiner(",","[","]");
            for(JsonElement item:e.getAsJsonArray()) if(!item.isJsonNull()) out.add(snbt(item));
            return out.toString();
        }
        JsonPrimitive p=e.getAsJsonPrimitive();
        if(p.isBoolean()) return String.valueOf(p.getAsBoolean());
        if(p.isNumber()) {
            String n=p.getAsString();
            return n.contains(".") || n.contains("e") || n.contains("E") ? n+"d" : n;
        }
        return quote(p.getAsString());
    }
    private static String quote(String s) { return "\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\""; }
}
