package konqasasas.gauntlet.data;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;
import konqasasas.gauntlet.chat.Languages;
import konqasasas.gauntlet.play.RunRecord;

/**
 * One-time import of the files used before the database: records.jsonl (runs) and
 * chat-prefs.json (translation settings). Each file is renamed to *.migrated after
 * its rows are committed, so it is never imported twice and never lost.
 */
public final class LegacyImport {
    private record OldRun(String course,UUID player,String result,long ticks,double x,double y,double z,long time) {}
    private record OldPref(Boolean auto,String language) {}
    private record OldEntry(UUID player,OldPref pref) {}
    private record OldPrefs(int format,List<OldEntry> players) {}

    public static void run(Database db,Path dataFolder,Logger logger) throws IOException {
        Path records=dataFolder.resolve("records.jsonl");
        if(Files.isRegularFile(records)) {
            int[] counts=importRuns(db,records);
            retire(records);
            logger.info("Imported "+counts[0]+" runs from records.jsonl"+(counts[1]>0 ? " (skipped "+counts[1]+" unreadable lines)" : ""));
        }
        Path prefs=dataFolder.resolve("chat-prefs.json");
        if(Files.isRegularFile(prefs)) {
            int count=importPrefs(db,prefs);
            retire(prefs);
            logger.info("Imported chat settings of "+count+" players from chat-prefs.json");
        }
    }
    static int[] importRuns(Database db,Path file) throws IOException {
        Gson gson=new Gson();
        List<RunRecord> runs=new ArrayList<>(); int skipped=0;
        for(String line:Files.readAllLines(file,StandardCharsets.UTF_8)) {
            if(line.isBlank()) continue;
            try {
                OldRun old=gson.fromJson(line,OldRun.class);
                RunRecord.Result result=RunRecord.Result.valueOf(old.result());
                // Old files have no revision, progress or start time.
                runs.add(new RunRecord(UUID.randomUUID(),old.course(),0,old.player(),result,null,old.ticks(),null,null,null,old.time(),
                        old.x(),old.y(),old.z(),null,"legacy"));
            } catch(RuntimeException e) { skipped++; }
        }
        db.call(c -> Database.transaction(c,tx -> { for(RunRecord r:runs) RunLog.insert(tx,r); return null; }));
        return new int[]{runs.size(),skipped};
    }
    static int importPrefs(Database db,Path file) throws IOException {
        OldPrefs prefs;
        try { prefs=new Gson().fromJson(Files.readString(file,StandardCharsets.UTF_8),OldPrefs.class); }
        catch(RuntimeException e) { throw new IOException("chat-prefs.json is invalid; left in place",e); }
        if(prefs==null || prefs.players()==null) return 0;
        List<OldEntry> entries=prefs.players().stream().filter(e -> e!=null && e.player()!=null && e.pref()!=null).toList();
        db.call(c -> Database.transaction(c,tx -> {
            try(PreparedStatement s=tx.prepareStatement("INSERT OR REPLACE INTO player_settings(uuid,key,value) VALUES(?,?,?)")) {
                for(OldEntry e:entries) {
                    if(Boolean.TRUE.equals(e.pref().auto())) add(s,e.player(),Settings.TRANSLATE_AUTO.key(),"true");
                    String code=Languages.normalize(e.pref().language());
                    if(code!=null) add(s,e.player(),Settings.TRANSLATE_LANG.key(),code);
                }
                s.executeBatch();
            }
            return null;
        }));
        return entries.size();
    }
    private static void add(PreparedStatement s,UUID player,String key,String value) throws SQLException {
        s.setString(1,player.toString()); s.setString(2,key); s.setString(3,value); s.addBatch();
    }
    private static void retire(Path file) throws IOException {
        Path target=file.resolveSibling(file.getFileName()+".migrated");
        if(Files.exists(target)) target=file.resolveSibling(file.getFileName()+"."+System.currentTimeMillis()+".migrated");
        Files.move(file,target);
    }
    private LegacyImport() {}
}
