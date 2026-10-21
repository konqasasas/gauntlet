package konqasasas.gauntlet.course;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import konqasasas.gauntlet.io.AtomicFiles;

/**
 * Placeable cells outside every course (placeable.json), for trying out a section
 * with the builder kit before it becomes a course. A broken file is never overwritten.
 */
public final class FreePlaceableStore {
    private record WorldCells(UUID world,List<BlockPos> cells) {}
    private record SavedFile(int format,List<WorldCells> worlds) {}
    private final Path file;
    private final Gson gson=new GsonBuilder().setPrettyPrinting().create();
    private final Map<UUID,Set<BlockPos>> cells=new LinkedHashMap<>();

    public FreePlaceableStore(Path file) throws IOException {
        this.file=file;
        if(!Files.exists(file)) return;
        try {
            SavedFile saved=gson.fromJson(Files.readString(file),SavedFile.class);
            if(saved==null || saved.format()!=1 || saved.worlds()==null) throw new IllegalArgumentException("Unsupported format");
            for(WorldCells w:saved.worlds()) {
                if(w==null || w.world()==null || w.cells()==null) throw new IllegalArgumentException("Invalid entry");
                cells.computeIfAbsent(w.world(),k -> new LinkedHashSet<>()).addAll(w.cells());
            }
        } catch(RuntimeException e) { throw new IOException("Placeable file is invalid; preserved: "+file,e); }
    }
    public boolean contains(UUID world,BlockPos cell) { return cells.getOrDefault(world,Set.of()).contains(cell); }
    public Set<BlockPos> cells(UUID world) { return Set.copyOf(cells.getOrDefault(world,Set.of())); }
    /** Adds the cell, or removes it when already registered; true when it was added. */
    public boolean toggle(UUID world,BlockPos cell) throws IOException {
        Map<UUID,Set<BlockPos>> next=copy();
        Set<BlockPos> set=next.computeIfAbsent(world,k -> new LinkedHashSet<>());
        boolean added=set.add(cell);
        if(!added) set.remove(cell);
        write(next); return added;
    }
    /** Takes out and returns the cells inside the region (for moving them into a course). */
    public List<BlockPos> take(UUID world,Region region) throws IOException {
        List<BlockPos> inside=cells.getOrDefault(world,Set.of()).stream().filter(c -> region.contains(c.x(),c.y(),c.z())).toList();
        if(inside.isEmpty()) return inside;
        Map<UUID,Set<BlockPos>> next=copy();
        inside.forEach(next.get(world)::remove);
        write(next); return inside;
    }
    private Map<UUID,Set<BlockPos>> copy() {
        Map<UUID,Set<BlockPos>> next=new LinkedHashMap<>();
        cells.forEach((w,set) -> next.put(w,new LinkedHashSet<>(set)));
        return next;
    }
    private void write(Map<UUID,Set<BlockPos>> next) throws IOException {
        next.values().removeIf(Set::isEmpty);
        List<WorldCells> worlds=next.entrySet().stream().map(e -> new WorldCells(e.getKey(),List.copyOf(e.getValue()))).toList();
        AtomicFiles.write(file,gson.toJson(new SavedFile(1,worlds)).getBytes(StandardCharsets.UTF_8));
        cells.clear(); cells.putAll(next);
    }
}
