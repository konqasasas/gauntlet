package konqasasas.gauntlet.course;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import konqasasas.gauntlet.io.AtomicFiles;

/** Every era, in eras.json. A course belongs to at most one era. */
public final class EraStore {
    private record SavedFile(int format,List<Era> eras) {}
    private final Path file;
    private final Gson gson=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Map<String,Era> eras=new TreeMap<>();

    public EraStore(Path file) throws IOException {
        this.file=file;
        if(!Files.exists(file)) return;
        try {
            SavedFile saved=gson.fromJson(Files.readString(file),SavedFile.class);
            if(saved==null || saved.format()!=1 || saved.eras()==null) throw new IllegalArgumentException("Unsupported format");
            for(Era era:saved.eras()) { if(era==null) throw new IllegalArgumentException("Invalid era"); eras.put(era.id(),era); }
        } catch(RuntimeException e) { throw new IOException("Era file is invalid; preserved: "+file,e); }
    }
    public List<Era> all() { return List.copyOf(eras.values()); }
    public Era get(String id) { return eras.get(id); }
    /** The era the course belongs to, or null. */
    public Era of(String course) {
        for(Era era:eras.values()) if(era.courses().contains(course)) return era;
        return null;
    }
    /** Adds or replaces; a course listed in this era leaves any other. */
    public void save(Era era) throws IOException {
        Map<String,Era> next=new TreeMap<>(eras);
        for(var e:next.entrySet()) if(!e.getKey().equals(era.id())) {
            Era other=e.getValue();
            List<String> kept=other.courses().stream().filter(c -> !era.courses().contains(c)).toList();
            if(kept.size()!=other.courses().size()) e.setValue(other.withCourses(kept));
        }
        next.put(era.id(),era); write(next);
    }
    public void delete(String id) throws IOException { Map<String,Era> next=new TreeMap<>(eras); next.remove(id); write(next); }
    private void write(Map<String,Era> next) throws IOException {
        AtomicFiles.write(file,gson.toJson(new SavedFile(1,List.copyOf(next.values()))).getBytes(StandardCharsets.UTF_8));
        eras.clear(); eras.putAll(next);
    }
}
