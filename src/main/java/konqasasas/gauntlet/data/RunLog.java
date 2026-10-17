package konqasasas.gauntlet.data;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import konqasasas.gauntlet.play.RunRecord;

/**
 * The runs table plus per (course, revision, player) totals kept in memory, so
 * the scoreboard and the goal screen never query the database.
 */
public final class RunLog {
    /** bestProgress / bestClearTicks are null when there is none yet. */
    public record Stats(int attempts,int clears,Double bestProgress,Long bestClearTicks) {
        public static final Stats EMPTY=new Stats(0,0,null,null);
        Stats add(RunRecord r) {
            boolean clear=r.result()==RunRecord.Result.CLEAR;
            // Plain ifs: a mixed boxed/primitive ternary would unbox the null fields.
            Double progress=clear ? Double.valueOf(1) : r.progress();
            Double best=bestProgress;
            if(progress!=null && (best==null || progress>best)) best=progress;
            Long ticks=bestClearTicks;
            if(clear && (ticks==null || r.ticks()<ticks)) ticks=r.ticks();
            return new Stats(attempts+1,clears+(clear ? 1 : 0),best,ticks);
        }
    }
    private record Key(String course,int rev,UUID player) {}
    private final Database db;
    private final Map<Key,Stats> stats=new ConcurrentHashMap<>();

    public RunLog(Database db) { this.db=db; }
    /** Reads the totals once at startup. */
    public void load() throws IOException {
        Map<Key,Stats> read=db.call(c -> {
            Map<Key,Stats> out=new HashMap<>();
            try(Statement s=c.createStatement(); ResultSet r=s.executeQuery(
                    "SELECT course,rev,player,COUNT(*),SUM(result='CLEAR'),MAX(CASE WHEN result='CLEAR' THEN 1.0 ELSE progress END),"
                    +"MIN(CASE WHEN result='CLEAR' THEN ticks END) FROM runs GROUP BY course,rev,player")) {
                while(r.next()) {
                    double best=r.getDouble(6); boolean noBest=r.wasNull();
                    long clear=r.getLong(7); boolean noClear=r.wasNull();
                    out.put(new Key(r.getString(1),r.getInt(2),UUID.fromString(r.getString(3))),
                            new Stats(r.getInt(4),r.getInt(5),noBest ? null : best,noClear ? null : clear));
                }
            }
            return out;
        });
        stats.clear(); stats.putAll(read);
    }
    public Stats stats(String course,int rev,UUID player) { return stats.getOrDefault(new Key(course,rev,player),Stats.EMPTY); }
    /** Counts the attempt now; the row is written in the background. */
    public void append(RunRecord r) {
        stats.merge(new Key(r.course(),r.rev(),r.player()),Stats.EMPTY.add(r),(old,one) -> old.add(r));
        db.write("run "+r.id(),c -> insert(c,r));
    }
    static int insert(Connection c,RunRecord r) throws SQLException {
        try(PreparedStatement s=c.prepareStatement("INSERT INTO runs(id,course,rev,player,result,reason,ticks,wall_ms,progress,started_at,ended_at,x,y,z,lane,plugin) "
                +"VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            s.setString(1,r.id().toString()); s.setString(2,r.course()); s.setInt(3,r.rev()); s.setString(4,r.player().toString());
            s.setString(5,r.result().name()); s.setString(6,r.reason()); s.setLong(7,r.ticks());
            setLong(s,8,r.wallMs()); if(r.progress()==null) s.setNull(9,Types.REAL); else s.setDouble(9,r.progress());
            setLong(s,10,r.startedAt()); s.setLong(11,r.endedAt());
            s.setDouble(12,r.x()); s.setDouble(13,r.y()); s.setDouble(14,r.z());
            if(r.lane()==null) s.setNull(15,Types.INTEGER); else s.setInt(15,r.lane());
            s.setString(16,r.plugin());
            return s.executeUpdate();
        }
    }
    private static void setLong(PreparedStatement s,int index,Long value) throws SQLException {
        if(value==null) s.setNull(index,Types.INTEGER); else s.setLong(index,value);
    }
}
