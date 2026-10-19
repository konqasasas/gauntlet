package konqasasas.gauntlet.course;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import konqasasas.gauntlet.io.AtomicFiles;

/**
 * Triggers outside every course (triggers.json), for trying out a section with
 * the builder kit before it becomes a course. A broken file is never overwritten.
 */
public final class FreeTriggerStore {
    public record Key(UUID world,BlockPos pos) {}
    public record Entry(UUID world,Trigger trigger) {
        public Entry {
            if(world==null || trigger==null) throw new IllegalArgumentException("Invalid free trigger");
        }
        public Key key() { return new Key(world,trigger.pos()); }
    }
    private record SavedFile(int format,List<Entry> triggers) {}
    private final Path file;
    private final Gson gson=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Map<Key,Entry> entries=new LinkedHashMap<>();

    public FreeTriggerStore(Path file) throws IOException {
        this.file=file;
        if(!Files.exists(file)) return;
        try {
            SavedFile saved=gson.fromJson(Files.readString(file),SavedFile.class);
            if(saved==null || saved.format()!=1 || saved.triggers()==null) throw new IllegalArgumentException("Unsupported format");
            for(Entry e:saved.triggers()) if(e==null || entries.putIfAbsent(e.key(),e)!=null) throw new IllegalArgumentException("Invalid/duplicate trigger");
        } catch(RuntimeException e) { throw new IOException("Trigger file is invalid; preserved: "+file,e); }
    }
    public List<Entry> all() { return List.copyOf(entries.values()); }
    public Trigger get(UUID world,BlockPos pos) {
        Entry e=entries.get(new Key(world,pos)); return e==null ? null : e.trigger();
    }
    /** Adds or replaces the trigger at its position. */
    public void put(UUID world,Trigger trigger) throws IOException {
        var next=new LinkedHashMap<>(entries); Entry e=new Entry(world,trigger); next.put(e.key(),e); write(next);
    }
    public void remove(Collection<Key> keys) throws IOException {
        var next=new LinkedHashMap<>(entries); keys.forEach(next::remove); write(next);
    }
    private void write(Map<Key,Entry> next) throws IOException {
        AtomicFiles.write(file,gson.toJson(new SavedFile(1,List.copyOf(next.values()))).getBytes(StandardCharsets.UTF_8));
        entries.clear(); entries.putAll(next);
    }
}
