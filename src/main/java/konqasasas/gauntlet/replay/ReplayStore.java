package konqasasas.gauntlet.replay;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import konqasasas.gauntlet.data.Database;
import konqasasas.gauntlet.io.AtomicFiles;

/**
 * Replay files (replays/<run id>.replay) and their index (gauntlet.db: replays).
 * For each player, course and revision only the best clear and the latest few
 * clears are kept; over the size cap the oldest replays that are nobody's best go
 * first. Every method touches the disk or the database: call them off the main thread.
 */
public final class ReplayStore {
    public record Entry(UUID run,UUID player,String course,int rev,long ticks,long createdAt,long bytes) {}
    private final Database db;
    private final Path dir;
    private final int recent;
    private final long maxBytes;
    public ReplayStore(Database db,Path dir,int recent,long maxBytes) { this.db=db; this.dir=dir; this.recent=recent; this.maxBytes=maxBytes; }

    private Path file(UUID run) { return dir.resolve(run+".replay"); }
    /** Writes the replay, then drops the ones no longer kept; the runs dropped. */
    public List<UUID> save(Replay replay) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        replay.write(bytes);
        AtomicFiles.write(file(replay.run()),bytes.toByteArray());
        Entry entry=new Entry(replay.run(),replay.player(),replay.course(),replay.rev(),replay.ticks(),System.currentTimeMillis(),bytes.size());
        db.call(c -> {
            try(PreparedStatement s=c.prepareStatement("INSERT OR REPLACE INTO replays(run,player,course,rev,ticks,created_at,bytes) VALUES(?,?,?,?,?,?,?)")) {
                s.setString(1,entry.run().toString()); s.setString(2,entry.player().toString()); s.setString(3,entry.course()); s.setInt(4,entry.rev());
                s.setLong(5,entry.ticks()); s.setLong(6,entry.createdAt()); s.setLong(7,entry.bytes()); return s.executeUpdate();
            }
        });
        List<Entry> all=entries(null);
        List<UUID> drop=drop(all,recent,maxBytes);
        for(UUID run:drop) delete(run);
        return drop;
    }
    /** Which replays to drop: in each player/course/revision all but the best and the latest `recent`; then, over maxBytes, the oldest non-best ones, then the oldest. */
    static List<UUID> drop(List<Entry> all,int recent,long maxBytes) {
        Map<String,List<Entry>> groups=new HashMap<>();
        for(Entry e:all) groups.computeIfAbsent(e.player()+"/"+e.course()+"/"+e.rev(),k -> new ArrayList<>()).add(e);
        Set<UUID> best=new HashSet<>(),drop=new LinkedHashSet<>();
        for(List<Entry> group:groups.values()) {
            Entry b=group.stream().min(Comparator.comparingLong(Entry::ticks).thenComparingLong(Entry::createdAt)).orElseThrow();
            best.add(b.run());
            List<Entry> newest=group.stream().sorted(Comparator.comparingLong(Entry::createdAt).reversed()).toList();
            for(int i=0;i<newest.size();i++) if(i>=recent && !newest.get(i).run().equals(b.run())) drop.add(newest.get(i).run());
        }
        long total=all.stream().filter(e -> !drop.contains(e.run())).mapToLong(Entry::bytes).sum();
        List<Entry> oldest=all.stream().filter(e -> !drop.contains(e.run())).sorted(Comparator.comparing((Entry e) -> best.contains(e.run())).thenComparingLong(Entry::createdAt)).toList();
        for(Entry e:oldest) { if(total<=maxBytes) break; drop.add(e.run()); total-=e.bytes(); }
        return List.copyOf(drop);
    }
    public void delete(UUID run) throws IOException {
        Files.deleteIfExists(file(run));
        db.call(c -> { try(PreparedStatement s=c.prepareStatement("DELETE FROM replays WHERE run=?")) { s.setString(1,run.toString()); return s.executeUpdate(); } });
    }
    /** All replays, or one player's (newest first). */
    public List<Entry> entries(UUID player) throws IOException {
        return db.call(c -> {
            List<Entry> out=new ArrayList<>();
            try(PreparedStatement s=c.prepareStatement("SELECT run,player,course,rev,ticks,created_at,bytes FROM replays"+(player==null ? "" : " WHERE player=?")+" ORDER BY created_at DESC")) {
                if(player!=null) s.setString(1,player.toString());
                try(ResultSet r=s.executeQuery()) {
                    while(r.next()) out.add(new Entry(UUID.fromString(r.getString(1)),UUID.fromString(r.getString(2)),r.getString(3),r.getInt(4),r.getLong(5),r.getLong(6),r.getLong(7)));
                }
            }
            return out;
        });
    }
    /** The replay whose run id starts with the prefix (8 characters or more), or null. */
    public Replay load(String prefix) throws IOException {
        if(prefix.length()<8) return null;
        String run=db.call(c -> {
            try(PreparedStatement s=c.prepareStatement("SELECT run FROM replays WHERE run LIKE ? LIMIT 2")) {
                s.setString(1,prefix+"%");
                try(ResultSet r=s.executeQuery()) { String found=r.next() ? r.getString(1) : null; return r.next() ? null : found; }
            }
        });
        if(run==null) return null;
        try(InputStream in=Files.newInputStream(file(UUID.fromString(run)))) { return Replay.read(in); }
    }
}
