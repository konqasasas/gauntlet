package konqasasas.gauntlet.play;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import konqasasas.gauntlet.io.AtomicFiles;

/**
 * What a player had before entering participant mode. Written before the
 * inventory is replaced, so a crash mid-run still gives everything back on join.
 */
public final class ReturnStore {
    /** inventory is Paper's ItemStack.serializeItemsAsBytes, Base64-encoded. */
    public record Saved(UUID world,double x,double y,double z,float yaw,float pitch,String gameMode,boolean allowFlight,String inventory) {
        public Saved {
            if(world==null || gameMode==null || inventory==null) throw new IllegalArgumentException("Incomplete return state");
        }
    }
    private record Entry(UUID player,Saved saved) {}
    private record SavedFile(int format,List<Entry> players) {}
    private final Path file;
    private final Gson gson=new GsonBuilder().setPrettyPrinting().create();
    private final Map<UUID,Saved> saved=new LinkedHashMap<>();

    public ReturnStore(Path file) throws IOException {
        this.file=file;
        if(!Files.exists(file)) return;
        try {
            SavedFile data=gson.fromJson(Files.readString(file),SavedFile.class);
            if(data==null || data.format()!=1 || data.players()==null) throw new IllegalArgumentException("Unsupported format");
            for(Entry entry:data.players()) {
                if(entry==null || entry.player()==null || entry.saved()==null) throw new IllegalArgumentException("Invalid entry");
                saved.put(entry.player(),entry.saved());
            }
        } catch(RuntimeException e) { throw new IOException("Return state file is invalid; preserved: "+file,e); }
    }
    public Saved get(UUID player) { return saved.get(player); }
    public void put(UUID player,Saved value) throws IOException {
        var next=new LinkedHashMap<>(saved); next.put(player,value); write(next);
    }
    public void remove(UUID player) throws IOException {
        if(!saved.containsKey(player)) return;
        var next=new LinkedHashMap<>(saved); next.remove(player); write(next);
    }
    private void write(Map<UUID,Saved> next) throws IOException {
        var entries=next.entrySet().stream().map(e -> new Entry(e.getKey(),e.getValue())).toList();
        AtomicFiles.write(file,gson.toJson(new SavedFile(1,entries)).getBytes(StandardCharsets.UTF_8));
        saved.clear(); saved.putAll(next);
    }
}
