package konqasasas.gauntlet.data;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How far each player got in the eras, and where to put them back after they
 * logged out mid-era. reached: the courses whose start they stepped on (first
 * time kept); resume: the course they were playing when they logged out. Both are
 * read once at startup and written in the background.
 */
public final class Progress {
    private final Database db;
    private final Map<UUID,Set<String>> reached=new ConcurrentHashMap<>();
    private final Map<UUID,String> resume=new ConcurrentHashMap<>();

    public Progress(Database db) { this.db=db; }
    public void load() throws IOException {
        db.call(c -> {
            try(Statement s=c.createStatement()) {
                try(ResultSet r=s.executeQuery("SELECT player,course FROM reached"))
                    { while(r.next()) reached.computeIfAbsent(UUID.fromString(r.getString(1)),k -> ConcurrentHashMap.newKeySet()).add(r.getString(2)); }
                try(ResultSet r=s.executeQuery("SELECT player,course FROM resume"))
                    { while(r.next()) resume.put(UUID.fromString(r.getString(1)),r.getString(2)); }
            }
            return null;
        });
    }
    public boolean reached(UUID player,String course) { return reached.getOrDefault(player,Set.of()).contains(course); }
    /** Records the course as reached (the first time only). */
    public void reach(UUID player,String course) {
        if(!reached.computeIfAbsent(player,k -> ConcurrentHashMap.newKeySet()).add(course)) return;
        long now=System.currentTimeMillis();
        db.write("reached "+player+" "+course,c -> {
            try(PreparedStatement s=c.prepareStatement("INSERT OR IGNORE INTO reached(player,course,first_at) VALUES(?,?,?)")) {
                s.setString(1,player.toString()); s.setString(2,course); s.setLong(3,now); return s.executeUpdate();
            }
        });
    }
    public String resume(UUID player) { return resume.get(player); }
    public void resume(UUID player,String course) {
        resume.put(player,course);
        long now=System.currentTimeMillis();
        db.write("resume "+player,c -> {
            try(PreparedStatement s=c.prepareStatement("INSERT OR REPLACE INTO resume(player,course,at) VALUES(?,?,?)")) {
                s.setString(1,player.toString()); s.setString(2,course); s.setLong(3,now); return s.executeUpdate();
            }
        });
    }
    public void clearResume(UUID player) {
        if(resume.remove(player)==null) return;
        db.write("resume "+player,c -> {
            try(PreparedStatement s=c.prepareStatement("DELETE FROM resume WHERE player=?")) { s.setString(1,player.toString()); return s.executeUpdate(); }
        });
    }
}
