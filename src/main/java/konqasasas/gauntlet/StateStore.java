package konqasasas.gauntlet;

import com.google.gson.*;
import java.io.IOException;
import konqasasas.gauntlet.io.AtomicFiles;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Atomic, durable state updates. A failed write does not change the in-memory state. */
final class StateStore {
    private record CpEntry(UUID owner,Checkpoint checkpoint) {}
    private record Saved(int format,List<CpEntry> checkpoints,List<PlacedBlock> blocks) {}
    private final Path file;
    private final Gson gson=new GsonBuilder().setPrettyPrinting().create();
    private final Map<UUID,Checkpoint> checkpoints=new LinkedHashMap<>();
    private final Map<BlockKey,PlacedBlock> blocks=new LinkedHashMap<>();
    StateStore(Path file) throws IOException {
        this.file=file;
        if(!Files.exists(file)) return;
        try {
            Saved saved=gson.fromJson(Files.readString(file),Saved.class);
            if(saved==null || saved.format()!=1 || saved.checkpoints()==null || saved.blocks()==null)
                throw new IllegalArgumentException("Unsupported or incomplete state file");
            for(CpEntry entry:saved.checkpoints()) {
                if(entry==null || entry.owner()==null || entry.checkpoint()==null || checkpoints.putIfAbsent(entry.owner(),entry.checkpoint())!=null)
                    throw new IllegalArgumentException("Invalid/duplicate checkpoint");
            }
            for(PlacedBlock block:saved.blocks()) {
                if(block==null || blocks.putIfAbsent(block.block(),block)!=null) throw new IllegalArgumentException("Invalid/duplicate block");
            }
        } catch(RuntimeException e) { throw new IOException("State file is invalid; preserved: "+file,e); }
    }
    Checkpoint checkpoint(UUID owner) { return checkpoints.get(owner); }
    boolean contains(BlockKey key) { return blocks.containsKey(key); }
    PlacedBlock block(BlockKey key) { return blocks.get(key); }
    List<PlacedBlock> allBlocks() { return List.copyOf(blocks.values()); }
    List<PlacedBlock> blocks(UUID owner) { return blocks.values().stream().filter(b -> b.owner().equals(owner)).toList(); }
    void checkpoint(UUID owner,Checkpoint checkpoint) throws IOException {
        var next=new LinkedHashMap<>(checkpoints); next.put(owner,checkpoint);
        save(next,blocks); checkpoints.clear(); checkpoints.putAll(next);
    }
    void removeCheckpoint(UUID owner) throws IOException {
        if(!checkpoints.containsKey(owner)) return;
        var next=new LinkedHashMap<>(checkpoints); next.remove(owner);
        save(next,blocks); checkpoints.clear(); checkpoints.putAll(next);
    }
    void add(PlacedBlock block) throws IOException {
        if(blocks.containsKey(block.block())) throw new IOException("Already registered: "+block.block());
        var next=new LinkedHashMap<>(blocks); next.put(block.block(),block);
        save(checkpoints,next); blocks.clear(); blocks.putAll(next);
    }
    void remove(Collection<BlockKey> keys) throws IOException {
        if(keys.isEmpty()) return;
        var next=new LinkedHashMap<>(blocks); keys.forEach(next::remove);
        save(checkpoints,next); blocks.clear(); blocks.putAll(next);
    }
    private void save(Map<UUID,Checkpoint> cps,Map<BlockKey,PlacedBlock> placed) throws IOException {
        var cpList=cps.entrySet().stream().map(e -> new CpEntry(e.getKey(),e.getValue())).toList();
        AtomicFiles.write(file,gson.toJson(new Saved(1,cpList,List.copyOf(placed.values()))).getBytes(StandardCharsets.UTF_8));
    }
}
