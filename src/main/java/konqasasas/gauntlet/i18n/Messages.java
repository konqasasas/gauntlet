package konqasasas.gauntlet.i18n;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.yaml.snakeyaml.Yaml;

/**
 * Message templates per language (MiniMessage strings). Bundled lang/<code>.yml
 * files, optionally overridden key by key by plugins/ParkourGauntlet/lang/<code>.yml.
 * Lookup order: the requested language, English, Japanese, then the key itself.
 */
public final class Messages {
    public static final List<String> LANGUAGES=List.of("ja","en");
    private static final List<String> FALLBACK=List.of("en","ja");
    private final Map<String,Map<String,String>> catalogs;
    private final Logger logger;
    private final Set<String> warned=ConcurrentHashMap.newKeySet();

    public Messages(Map<String,Map<String,String>> catalogs,Logger logger) {
        Map<String,Map<String,String>> copy=new HashMap<>();
        catalogs.forEach((lang,keys) -> copy.put(lang,Map.copyOf(keys)));
        this.catalogs=Map.copyOf(copy); this.logger=logger;
    }
    /** Bundled files first, then the server's override files on top. */
    public static Messages load(ClassLoader resources,Path overrides,Logger logger) throws IOException {
        Map<String,Map<String,String>> catalogs=new HashMap<>();
        for(String lang:LANGUAGES) {
            Map<String,String> keys=new HashMap<>();
            try(InputStream in=resources.getResourceAsStream("lang/"+lang+".yml")) {
                if(in==null) throw new FileNotFoundException("Bundled lang/"+lang+".yml missing");
                keys.putAll(parse(new InputStreamReader(in,StandardCharsets.UTF_8)));
            }
            Path override=overrides.resolve(lang+".yml");
            if(Files.isRegularFile(override)) try(Reader reader=Files.newBufferedReader(override,StandardCharsets.UTF_8)) {
                keys.putAll(parse(reader));
            } catch(RuntimeException e) { throw new IOException("Invalid language file "+override,e); }
            catalogs.put(lang,keys);
        }
        return new Messages(catalogs,logger);
    }
    /** Nested YAML flattened to dotted keys. A list becomes lines joined with \n. */
    public static Map<String,String> parse(Reader reader) {
        Object root=new Yaml().load(reader);
        Map<String,String> out=new LinkedHashMap<>();
        if(root instanceof Map<?,?> map) flatten("",map,out);
        else if(root!=null) throw new IllegalArgumentException("Language file must be a mapping");
        return out;
    }
    private static void flatten(String prefix,Map<?,?> map,Map<String,String> out) {
        for(var entry:map.entrySet()) {
            String key=prefix+entry.getKey();
            Object value=entry.getValue();
            if(value instanceof Map<?,?> child) flatten(key+".",child,out);
            else if(value instanceof List<?> list) out.put(key,String.join("\n",list.stream().map(String::valueOf).toList()));
            else if(value!=null) out.put(key,String.valueOf(value));
        }
    }
    /** The template, or null when no language has it (logged once). */
    public String template(String lang,String key) {
        Map<String,String> own=catalogs.get(lang);
        String value=own==null ? null : own.get(key);
        for(int i=0;value==null && i<FALLBACK.size();i++) value=catalogs.getOrDefault(FALLBACK.get(i),Map.of()).get(key);
        if(value==null && warned.add(key) && logger!=null) logger.warning("Missing message key: "+key);
        return value;
    }
    public Set<String> keys(String lang) { return catalogs.getOrDefault(lang,Map.of()).keySet(); }
}
