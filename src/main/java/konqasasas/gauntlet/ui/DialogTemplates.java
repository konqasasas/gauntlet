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
 * The editor's manifest.json (beside the templates) gives each screen's default
 * state values, used for the states the plugin does not know (sample values).
 */
public final class DialogTemplates {
    private static final Pattern CONDITION=Pattern.compile("([a-z0-9_.]{1,32})(!?=)([A-Za-z0-9_.-]{1,32})");
    private final String namespace;
    private static final String MANIFEST="manifest.json";
    private final Map<String,JsonObject> screens;
    private final Map<String,Map<String,String>> defaults;

    private DialogTemplates(String namespace,Map<String,JsonObject> screens,Map<String,Map<String,String>> defaults) {
        this.namespace=namespace; this.screens=Map.copyOf(screens); this.defaults=Map.copyOf(defaults);
    }
    public static DialogTemplates empty(String namespace) { return new DialogTemplates(namespace,Map.of(),Map.of()); }

    /** Every *.json in dir (but manifest.json), by file name. A missing dir reads as no templates. */
    public static DialogTemplates load(String namespace,Path dir) throws IOException {
        Map<String,JsonObject> screens=new HashMap<>();
        Map<String,Map<String,String>> defaults=new HashMap<>();
        if(Files.isDirectory(dir)) try(DirectoryStream<Path> files=Files.newDirectoryStream(dir,"*.json")) {
            for(Path file:files) {
                String name=file.getFileName().toString();
                JsonObject json;
                try { json=JsonParser.parseString(Files.readString(file,StandardCharsets.UTF_8)).getAsJsonObject(); }
                catch(JsonParseException|IllegalStateException e) { throw new IOException("Unreadable menu template "+file+": "+e.getMessage(),e); }
                if(name.equals(MANIFEST)) defaults.putAll(defaults(json));
                else screens.put(name.substring(0,name.length()-5),json);
            }
        }
        return new DialogTemplates(namespace,screens,defaults);
    }
    /** screens[].states.defaults of the editor's manifest, by screen id. */
    static Map<String,Map<String,String>> defaults(JsonObject manifest) {
        Map<String,Map<String,String>> out=new HashMap<>();
        if(!(manifest.get("screens") instanceof JsonArray list)) return out;
        for(JsonElement e:list) {
            if(!(e instanceof JsonObject screen) || !(screen.get("id") instanceof JsonPrimitive id)) continue;
            Map<String,String> values=new HashMap<>();
            if(screen.get("states") instanceof JsonObject states && states.get("defaults") instanceof JsonObject d)
                for(var entry:d.entrySet()) if(entry.getValue() instanceof JsonPrimitive p) values.put(entry.getKey(),p.getAsString());
            out.put(id.getAsString(),Map.copyOf(values));
        }
        return out;
    }
    public Set<String> screens() { return screens.keySet(); }
    public boolean has(String screen) { return screens.containsKey(screen); }
    /** The screen's default state values (from manifest.json); empty without a manifest. */
    public Map<String,String> defaults(String screen) { return defaults.getOrDefault(screen,Map.of()); }

    /** The screen with only the states matching values, ready for the component serializer; null if there is no template. */
    public JsonObject select(String screen,Map<String,String> values) {
        JsonObject template=screens.get(screen);
        if(template==null) return null;
        // The player's values win; the defaults fill in the states the plugin does not know.
        Map<String,String> all=new HashMap<>(defaults(screen));
        all.putAll(values);
        return select(template,namespace,all);
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
