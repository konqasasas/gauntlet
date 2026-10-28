package konqasasas.gauntlet.data;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Times through a module (ticks from entering it to entering the next one, or to the
 * goal) by anyone who went through without a reset inside it: no load of a practice
 * save, no flight, client ticks reported throughout. Official and practice both count,
 * in a generated course or in the module played alone. Kept per module revision, all
 * in memory (read once at startup), as a guide for setting module times.
 */
public final class ModuleTimes {
    /** Over the passes of one module revision: mean, median and the slower quarter (75th percentile), in ticks. */
    public record Summary(int passes,int players,double mean,double median,double slowQuarter) {}
    private record Key(String module,int rev) {}
    private record Pass(UUID player,long ticks) {}
    private final Database db;
    private final Map<Key,List<Pass>> passes=new ConcurrentHashMap<>();

    public ModuleTimes(Database db) { this.db=db; }
    public void load() throws IOException {
        Map<Key,List<Pass>> read=db.call(c -> {
            Map<Key,List<Pass>> out=new HashMap<>();
            try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT module,rev,player,ticks FROM module_times")) {
                while(r.next()) out.computeIfAbsent(new Key(r.getString(1),r.getInt(2)),k -> new ArrayList<>()).add(new Pass(UUID.fromString(r.getString(3)),r.getLong(4)));
            }
            return out;
        });
        passes.clear();
        read.forEach((k,v) -> passes.put(k,Collections.synchronizedList(v)));
    }
    /** Counts the pass now; the row is written in the background. */
    public void add(String module,int rev,UUID player,long ticks,boolean practice,boolean generated) {
        passes.computeIfAbsent(new Key(module,rev),k -> Collections.synchronizedList(new ArrayList<>())).add(new Pass(player,ticks));
        long at=System.currentTimeMillis();
        db.write("module time "+module,c -> {
            try(PreparedStatement s=c.prepareStatement("INSERT INTO module_times(module,rev,player,ticks,practice,generated,at) VALUES(?,?,?,?,?,?,?)")) {
                s.setString(1,module); s.setInt(2,rev); s.setString(3,player.toString()); s.setLong(4,ticks);
                s.setInt(5,practice ? 1 : 0); s.setInt(6,generated ? 1 : 0); s.setLong(7,at);
                return s.executeUpdate();
            }
        });
    }
    /** null when nobody has gone through this revision yet. */
    public Summary summary(String module,int rev) {
        List<Pass> list=passes.get(new Key(module,rev));
        if(list==null) return null;
        List<Pass> copy; synchronized(list) { copy=List.copyOf(list); }
        return summarize(copy.stream().mapToLong(Pass::ticks).toArray(),(int)copy.stream().map(Pass::player).distinct().count());
    }
    static Summary summarize(long[] ticks,int players) {
        if(ticks.length==0) return null;
        long[] sorted=ticks.clone(); Arrays.sort(sorted);
        return new Summary(sorted.length,players,Arrays.stream(sorted).average().orElse(0),quantile(sorted,.5),quantile(sorted,.75));
    }
    /** Linear interpolation between the closest ranks (sorted ascending). */
    static double quantile(long[] sorted,double q) {
        double pos=q*(sorted.length-1); int lo=(int)Math.floor(pos),hi=(int)Math.ceil(pos);
        return sorted[lo]+(sorted[hi]-sorted[lo])*(pos-lo);
    }
}
