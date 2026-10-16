package konqasasas.gauntlet.play;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Append-only JSON Lines log of every attempt; personal bests are derived from it. */
public final class RecordStore {
    private record Key(String course,UUID player) {}
    private final Path file;
    private final Gson gson=new Gson();
    private final Map<Key,Long> best=new HashMap<>();
    private int invalidLines;

    public RecordStore(Path file) throws IOException {
        this.file=file;
        if(!Files.exists(file)) return;
        for(String line:Files.readAllLines(file,StandardCharsets.UTF_8)) {
            if(line.isBlank()) continue;
            try { remember(gson.fromJson(line,RunRecord.class)); }
            // A torn last line after a crash must not lose the rest of the history.
            catch(RuntimeException e) { invalidLines++; }
        }
    }
    public int invalidLines() { return invalidLines; }
    public OptionalLong best(String course,UUID player) {
        Long ticks=best.get(new Key(course,player)); return ticks==null ? OptionalLong.empty() : OptionalLong.of(ticks);
    }
    public void append(RunRecord record) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file,gson.toJson(record)+"\n",StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,StandardOpenOption.APPEND,StandardOpenOption.DSYNC);
        remember(record);
    }
    private void remember(RunRecord record) {
        if(record==null) throw new IllegalArgumentException("Empty record");
        if(record.result()==RunRecord.Result.CLEAR) best.merge(new Key(record.course(),record.player()),record.ticks(),Math::min);
    }
}
