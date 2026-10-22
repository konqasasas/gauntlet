package konqasasas.gauntlet.cosmetic;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.yaml.snakeyaml.Yaml;

/**
 * The list of cosmetics (cosmetics.yml): the slots, the items and the bundles.
 *
 * Slots are data, so new ones are a file entry. Every item has an id, the slot it
 * fills, a theme (the look, also data) and how it is obtained: START (everyone has
 * it from the beginning), REWARD (earned only, never sold) or PURCHASE. Each slot
 * has one standard item (a START item), used when nothing else is chosen.
 * A bundle sells several PURCHASE items at once (a theme set).
 *
 * Proofs of skill (name colors, ranks, titles, trophies) are never cosmetics: a slot
 * with such a name is refused, so they can be neither granted nor sold here.
 */
public final class Catalog {
    public enum Obtain { START,REWARD,PURCHASE }
    /** Slot keys that must never be cosmetics: skill is shown, not bought. */
    static final Set<String> PROOFS=Set.of("name_color","rank","title","trophy");
    public record Item(String id,String slot,String theme,Obtain obtain,boolean standard) {
        /** Everyone has it, without a row. */
        public boolean everyone() { return obtain==Obtain.START; }
    }
    public record Bundle(String id,List<String> items) {}
    private final List<String> slots;
    private final Map<String,Item> items;
    private final Map<String,Bundle> bundles;
    private final Map<String,Item> standards;

    Catalog(List<String> slots,Map<String,Item> items,Map<String,Bundle> bundles) {
        this.slots=List.copyOf(slots);
        this.items=Collections.unmodifiableMap(new LinkedHashMap<>(items));
        this.bundles=Collections.unmodifiableMap(new LinkedHashMap<>(bundles));
        Map<String,Item> standards=new LinkedHashMap<>();
        for(Item item:items.values()) {
            if(!slots.contains(item.slot())) throw new IllegalArgumentException("Item "+item.id()+": unknown slot "+item.slot());
            if(item.standard() && standards.put(item.slot(),item)!=null) throw new IllegalArgumentException("Two standard items for "+item.slot());
        }
        for(String slot:slots) if(!standards.containsKey(slot)) throw new IllegalArgumentException("No standard item for "+slot);
        for(Bundle b:bundles.values()) for(String id:b.items()) {
            Item item=items.get(id);
            if(item==null || item.obtain()!=Obtain.PURCHASE) throw new IllegalArgumentException("Bundle "+b.id()+": "+id+" is not an item for sale");
        }
        this.standards=Collections.unmodifiableMap(standards);
    }
    /** The bundled cosmetics.yml with the server's file (null: none) on top: its slots are added, its items and bundles added or replaced. */
    public static Catalog load(ClassLoader resources,Path override) throws IOException {
        Parsed all=new Parsed();
        try(InputStream in=resources.getResourceAsStream("cosmetics.yml")) {
            if(in==null) throw new FileNotFoundException("Bundled cosmetics.yml missing");
            all.add(parse(new InputStreamReader(in,StandardCharsets.UTF_8)));
        } catch(RuntimeException e) { throw new IOException("Invalid bundled cosmetics.yml: "+e.getMessage(),e); }
        if(override!=null && Files.isRegularFile(override)) {
            try(Reader reader=Files.newBufferedReader(override,StandardCharsets.UTF_8)) { all.add(parse(reader)); }
            catch(RuntimeException e) { throw new IOException("Invalid "+override+": "+e.getMessage(),e); }
        }
        try { return new Catalog(all.slots,all.items,all.bundles); }
        catch(IllegalArgumentException e) { throw new IOException("Invalid cosmetics: "+e.getMessage(),e); }
    }
    static final class Parsed {
        final List<String> slots=new ArrayList<>();
        final Map<String,Item> items=new LinkedHashMap<>();
        final Map<String,Bundle> bundles=new LinkedHashMap<>();
        void add(Parsed p) { for(String s:p.slots) if(!slots.contains(s)) slots.add(s); items.putAll(p.items); bundles.putAll(p.bundles); }
    }
    /** slots: [key, ...]; items: { id: { slot, theme, obtain, standard } }; bundles: { id: { items: [...] } } */
    static Parsed parse(Reader reader) {
        Object root=new Yaml().load(reader);
        Parsed out=new Parsed();
        if(root==null) return out;
        if(!(root instanceof Map<?,?> map)) throw new IllegalArgumentException("Not a map");
        if(map.get("slots") instanceof List<?> list) for(Object o:list) {
            String slot=String.valueOf(o);
            if(PROOFS.contains(slot)) throw new IllegalArgumentException(slot+" is a proof of skill and cannot be a cosmetic slot");
            if(!slot.matches("[a-z0-9_]{1,32}")) throw new IllegalArgumentException("Bad slot "+slot);
            if(!out.slots.contains(slot)) out.slots.add(slot);
        }
        if(map.get("items") instanceof Map<?,?> list) for(var e:list.entrySet()) {
            String id=String.valueOf(e.getKey());
            if(!id.matches("[a-z0-9_.-]{1,64}")) throw new IllegalArgumentException("Bad item id "+id);
            if(!(e.getValue() instanceof Map<?,?> v)) throw new IllegalArgumentException("Item "+id+" needs slot/theme/obtain");
            String slot=String.valueOf(v.get("slot"));
            if(PROOFS.contains(slot)) throw new IllegalArgumentException("Item "+id+": "+slot+" is a proof of skill and cannot be a cosmetic");
            Object theme=v.get("theme");
            if(theme==null || String.valueOf(theme).isBlank()) throw new IllegalArgumentException("Item "+id+" needs a theme");
            boolean standard=Boolean.TRUE.equals(v.get("standard"));
            Obtain obtain;
            try { obtain=v.get("obtain")==null && standard ? Obtain.START : Obtain.valueOf(String.valueOf(v.get("obtain")).toUpperCase(Locale.ROOT)); }
            catch(IllegalArgumentException x) { throw new IllegalArgumentException("Item "+id+": obtain must be start, reward or purchase"); }
            if(standard && obtain!=Obtain.START) throw new IllegalArgumentException("Item "+id+": a standard item is obtain: start");
            out.items.put(id,new Item(id,slot,String.valueOf(theme),obtain,standard));
        }
        if(map.get("bundles") instanceof Map<?,?> list) for(var e:list.entrySet()) {
            String id=String.valueOf(e.getKey());
            if(!id.matches("[a-z0-9_.-]{1,64}")) throw new IllegalArgumentException("Bad bundle id "+id);
            if(!(e.getValue() instanceof Map<?,?> v) || !(v.get("items") instanceof List<?> items) || items.isEmpty())
                throw new IllegalArgumentException("Bundle "+id+" needs items");
            out.bundles.put(id,new Bundle(id,items.stream().map(String::valueOf).distinct().toList()));
        }
        return out;
    }
    public List<String> slots() { return slots; }
    public Item get(String id) { return items.get(id); }
    public Collection<Item> all() { return items.values(); }
    public Bundle bundle(String id) { return bundles.get(id); }
    public Collection<Bundle> bundles() { return bundles.values(); }
    public Item standard(String slot) { return standards.get(slot); }
}
