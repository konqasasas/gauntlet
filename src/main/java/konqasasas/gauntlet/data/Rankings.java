package konqasasas.gauntlet.data;

import java.io.IOException;
import java.sql.*;
import java.util.*;

/**
 * Rankings read from gauntlet.db.
 *
 * Course: official clears only (not INVALID, not taken off), one row per player
 * with their best time, ties to whoever set it first. Only the course's current
 * revision, plus the revisions it carries over (rev_carry: a builder chose to keep
 * the previous revision's records when the course changed).
 *
 * Rank: who reached the rank first (rank_ups).
 *
 * Taking records off the ranking marks them (voided_*: who, when, why) and never
 * deletes them, so they can be put back; ranks are not touched.
 * Every method reads or writes the database: call them off the main thread.
 */
public final class Rankings {
    public static final int TOP=10;
    /** One row: place from 1. ticks for courses, at (epoch ms) for ranks. */
    public record Row(int place,UUID player,long ticks,long at) {}
    /** The top rows, and the viewer's own row (null when they have none). */
    public record Board(List<Row> top,Row mine,int entries,List<Integer> revs) {}
    /** A run of a player, for builders choosing what to take off. */
    public record Run(String id,int rev,long ticks,long at,boolean voided,String reason) {}
    private final Database db;
    public Rankings(Database db) { this.db=db; }

    /** The revisions whose records count for this one: itself, then the ones it carries over, newest first. */
    public List<Integer> revs(String course,int rev) throws IOException {
        return db.call(c -> {
            List<Integer> revs=new ArrayList<>(List.of(rev));
            try(PreparedStatement s=c.prepareStatement("SELECT from_rev FROM rev_carry WHERE course=? AND rev=?")) {
                for(int r=rev;;) {
                    s.setString(1,course); s.setInt(2,r);
                    try(ResultSet q=s.executeQuery()) {
                        if(!q.next()) break;
                        r=q.getInt(1);
                        if(revs.contains(r)) break;
                        revs.add(r);
                    }
                }
            }
            return revs;
        });
    }
    public Board course(String course,int rev,UUID viewer) throws IOException {
        List<Integer> revs=revs(course,rev);
        record Clear(UUID player,long ticks,long at) {}
        List<Clear> clears=db.call(c -> {
            List<Clear> out=new ArrayList<>();
            String in=String.join(",",Collections.nCopies(revs.size(),"?"));
            try(PreparedStatement s=c.prepareStatement("SELECT player,ticks,ended_at FROM runs WHERE course=? AND result='CLEAR' AND voided_at IS NULL AND rev IN ("+in+")")) {
                s.setString(1,course);
                for(int i=0;i<revs.size();i++) s.setInt(i+2,revs.get(i));
                try(ResultSet r=s.executeQuery()) { while(r.next()) out.add(new Clear(UUID.fromString(r.getString(1)),r.getLong(2),r.getLong(3))); }
            }
            return out;
        });
        Map<UUID,Clear> best=new HashMap<>();
        for(Clear x:clears) best.merge(x.player(),x,(a,b) -> b.ticks()<a.ticks() || (b.ticks()==a.ticks() && b.at()<a.at()) ? b : a);
        List<Clear> order=best.values().stream().sorted(Comparator.comparingLong(Clear::ticks).thenComparingLong(Clear::at)).toList();
        return board(order.stream().map(x -> new long[]{x.ticks(),x.at()}).toList(),order.stream().map(Clear::player).toList(),viewer,revs);
    }
    public Board rank(int rank,UUID viewer) throws IOException {
        record Up(UUID player,long at) {}
        List<Up> ups=db.call(c -> {
            List<Up> out=new ArrayList<>();
            try(PreparedStatement s=c.prepareStatement("SELECT player,at FROM rank_ups WHERE rank=? ORDER BY at,player")) {
                s.setInt(1,rank);
                try(ResultSet r=s.executeQuery()) { while(r.next()) out.add(new Up(UUID.fromString(r.getString(1)),r.getLong(2))); }
            }
            return out;
        });
        return board(ups.stream().map(u -> new long[]{0,u.at()}).toList(),ups.stream().map(Up::player).toList(),viewer,List.of());
    }
    private static Board board(List<long[]> values,List<UUID> players,UUID viewer,List<Integer> revs) {
        List<Row> top=new ArrayList<>(); Row mine=null;
        for(int i=0;i<players.size();i++) {
            Row row=new Row(i+1,players.get(i),values.get(i)[0],values.get(i)[1]);
            if(i<TOP) top.add(row);
            if(players.get(i).equals(viewer)) mine=row;
        }
        return new Board(top,mine,players.size(),revs);
    }

    // ---- Builders ----
    /** The player's official clears of the course (every revision), newest first. */
    public List<Run> runs(UUID player,String course) throws IOException {
        return db.call(c -> {
            List<Run> out=new ArrayList<>();
            try(PreparedStatement s=c.prepareStatement("SELECT id,rev,ticks,ended_at,voided_at,voided_reason FROM runs WHERE player=? AND course=? AND result='CLEAR' ORDER BY ended_at DESC")) {
                s.setString(1,player.toString()); s.setString(2,course);
                try(ResultSet r=s.executeQuery()) {
                    while(r.next()) { r.getLong(5); boolean voided=!r.wasNull(); out.add(new Run(r.getString(1),r.getInt(2),r.getLong(3),r.getLong(4),voided,r.getString(6))); }
                }
            }
            return out;
        });
    }
    /** Takes one run off the ranking (id or its first 8+ characters); how many runs it marked. */
    public int voidRun(String idPrefix,UUID by,String reason) throws IOException {
        return mark("UPDATE runs SET voided_at=?,voided_by=?,voided_reason=? WHERE id LIKE ? AND voided_at IS NULL",idPrefix,null,by,reason);
    }
    /** Takes every run of the player on the course off the ranking. */
    public int voidPlayer(UUID player,String course,UUID by,String reason) throws IOException {
        return mark("UPDATE runs SET voided_at=?,voided_by=?,voided_reason=? WHERE player=? AND course=? AND voided_at IS NULL",player.toString(),course,by,reason);
    }
    public int restoreRun(String idPrefix) throws IOException { return mark("UPDATE runs SET voided_at=NULL,voided_by=NULL,voided_reason=NULL WHERE id LIKE ?",idPrefix,null,null,null); }
    public int restorePlayer(UUID player,String course) throws IOException {
        return mark("UPDATE runs SET voided_at=NULL,voided_by=NULL,voided_reason=NULL WHERE player=? AND course=?",player.toString(),course,null,null);
    }
    private int mark(String sql,String a,String b,UUID by,String reason) throws IOException {
        if(a.length()<8 && b==null) return 0;
        long now=System.currentTimeMillis();
        return db.call(c -> {
            try(PreparedStatement s=c.prepareStatement(sql)) {
                int i=1;
                if(sql.contains("voided_at=?")) { s.setLong(i++,now); s.setString(i++,by==null ? null : by.toString()); s.setString(i++,reason); }
                s.setString(i++,b==null ? a+"%" : a);
                if(b!=null) s.setString(i,b);
                return s.executeUpdate();
            }
        });
    }
    /** The course's revision rev counts the records of from (or stops when from is null). */
    public void carry(String course,int rev,Integer from,UUID by) throws IOException {
        long now=System.currentTimeMillis();
        db.call(c -> {
            if(from==null) {
                try(PreparedStatement s=c.prepareStatement("DELETE FROM rev_carry WHERE course=? AND rev=?")) { s.setString(1,course); s.setInt(2,rev); return s.executeUpdate(); }
            }
            try(PreparedStatement s=c.prepareStatement("INSERT OR REPLACE INTO rev_carry(course,rev,from_rev,by,at) VALUES(?,?,?,?,?)")) {
                s.setString(1,course); s.setInt(2,rev); s.setInt(3,from); s.setString(4,by==null ? null : by.toString()); s.setLong(5,now); return s.executeUpdate();
            }
        });
    }
}
