package konqasasas.gauntlet.data;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;
import konqasasas.gauntlet.play.RunRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataStoreTest {
    @TempDir Path dir;
    private static final Logger LOG=Logger.getAnonymousLogger();

    private static RunRecord run(String course,int rev,UUID player,RunRecord.Result result,long ticks,Double progress) {
        return new RunRecord(UUID.randomUUID(),course,rev,player,result,result==RunRecord.Result.FAIL ? "LAVA" : null,ticks,ticks*50,progress,1L,2L,0,0,0,null,"test");
    }
    private static RunRecord clear(String course,int rev,UUID player,long ticks,long endedAt) {
        return new RunRecord(UUID.randomUUID(),course,rev,player,RunRecord.Result.CLEAR,null,ticks,ticks*50,1.0,1L,endedAt,0,0,0,1,"test");
    }
    @Test void rankingsBestPerPlayerTiesFirstCarryAndTakingOff() throws Exception {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID(),admin=UUID.randomUUID();
        try(Database db=Database.open(dir.resolve("g.db"),LOG)) {
            RunLog log=new RunLog(db);
            RunRecord aSlow=clear("x",1,a,300,10),aBest=clear("x",1,a,200,20);
            log.append(aSlow); log.append(aBest);
            log.append(clear("x",1,b,200,15));                          // same time as a's best, set earlier
            log.append(run("x",1,c,RunRecord.Result.INVALID,100,null)); // not a clear
            log.append(clear("x",0,c,150,5));                           // an older revision
            log.append(clear("y",1,c,50,5));                            // another course
            Rankings r=new Rankings(db);
            Rankings.Board board=r.course("x",1,a);
            assertEquals(List.of(b,a),board.top().stream().map(Rankings.Row::player).toList(),"Ties go to whoever set it first");
            assertEquals(200,board.mine().ticks()); assertEquals(2,board.mine().place()); assertEquals(2,board.entries());
            r.carry("x",1,0,admin);
            assertEquals(List.of(1,0),r.revs("x",1));
            assertEquals(c,r.course("x",1,a).top().getFirst().player(),"Carried over from revision 0");
            r.carry("x",1,null,admin);
            assertEquals(List.of(1),r.revs("x",1));
            assertEquals(1,r.voidRun(aBest.id().toString().substring(0,8),admin,"cheat"));
            assertEquals(0,r.voidRun("abc",admin,"too short"),"A run id needs 8 characters");
            assertEquals(300,r.course("x",1,a).mine().ticks(),"The other run counts");
            assertEquals(1,r.voidPlayer(a,"x",admin,"all"));
            assertNull(r.course("x",1,a).mine());
            assertTrue(r.runs(a,"x").stream().allMatch(Rankings.Run::voided),"Kept, only marked");
            assertEquals(2,r.restorePlayer(a,"x"));
            assertEquals(200,r.course("x",1,a).mine().ticks());
            Progress p=new Progress(db); p.load();
            p.setRank(b,1); Thread.sleep(5); p.setRank(a,1); p.setRank(a,2); p.setRank(a,1);
            assertEquals(List.of(b,a),r.rank(1,a).top().stream().map(Rankings.Row::player).toList(),"First to reach the rank");
            assertEquals(List.of(a),r.rank(2,a).top().stream().map(Rankings.Row::player).toList(),"Kept after the rank was set lower");
        }
    }
    @Test void reachedCoursesAndResumeSurviveRestart() throws Exception {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        try(Database db=Database.open(dir.resolve("g.db"),LOG)) {
            Progress p=new Progress(db); p.load();
            p.reach(a,"1-1"); p.reach(a,"1-2"); p.reach(a,"1-1");
            p.resume(a,"1-2"); p.resume(b,"1-1"); p.clearResume(b);
            assertTrue(p.reached(a,"1-2")); assertFalse(p.reached(b,"1-1"));
            assertEquals(0,p.rank(a),"Newcomers are rank 0");
            assertTrue(p.raise(a,2)); assertFalse(p.raise(a,1),"Never lowered by a boss"); assertFalse(p.raise(a,2));
            p.setRank(b,5); p.setRank(b,3);
        }
        try(Database db=Database.open(dir.resolve("g.db"),LOG)) {
            Progress p=new Progress(db); p.load();
            assertTrue(p.reached(a,"1-1")); assertTrue(p.reached(a,"1-2")); assertFalse(p.reached(a,"1-3"));
            assertEquals("1-2",p.resume(a)); assertNull(p.resume(b));
            assertEquals(2,p.rank(a)); assertEquals(3,p.rank(b),"The command may lower it");
            long rows=db.call(c -> { try(var s=c.createStatement(); var r=s.executeQuery("SELECT COUNT(*) FROM reached")) { r.next(); return r.getLong(1); } });
            assertEquals(2,rows,"Reaching again keeps one row");
        }
    }
    @Test void runTotalsAreKeptPerRevisionAndSurviveRestart() throws Exception {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        try(Database db=Database.open(dir.resolve("g.db"),LOG)) {
            RunLog log=new RunLog(db);
            log.append(run("c",0,a,RunRecord.Result.FAIL,100,.4));
            log.append(run("c",0,a,RunRecord.Result.FAIL,120,.87));
            log.append(run("c",0,a,RunRecord.Result.CLEAR,300,1.0));
            log.append(run("c",0,a,RunRecord.Result.CLEAR,250,1.0));
            log.append(run("c",1,a,RunRecord.Result.RETIRE,20,.1));
            log.append(run("c",0,b,RunRecord.Result.INVALID,50,null));
            assertEquals(new RunLog.Stats(4,2,1.0,250L),log.stats("c",0,a));
            assertEquals(new RunLog.Stats(1,0,.1,null),log.stats("c",1,a),"A new revision starts over");
            assertEquals(new RunLog.Stats(1,0,null,null),log.stats("c",0,b));
            assertEquals(RunLog.Stats.EMPTY,log.stats("other",0,a));
        }
        try(Database db=Database.open(dir.resolve("g.db"),LOG)) {
            RunLog log=new RunLog(db); log.load();
            assertEquals(new RunLog.Stats(4,2,1.0,250L),log.stats("c",0,a));
            assertEquals(new RunLog.Stats(1,0,.1,null),log.stats("c",1,a));
            assertEquals(new RunLog.Stats(1,0,null,null),log.stats("c",0,b));
        }
    }
    @Test void legacyFilesAreImportedOnceAndKept() throws Exception {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        Files.writeString(dir.resolve("records.jsonl"),
                "{\"course\":\"c\",\"player\":\""+a+"\",\"result\":\"CLEAR\",\"ticks\":300,\"x\":1,\"y\":2,\"z\":3,\"time\":5}\n"
                +"{\"course\":\"c\",\"player\":\""+a+"\",\"result\":\"FAIL\",\"ticks\":30,\"x\":1,\"y\":2,\"z\":3,\"time\":6}\n"
                +"{\"course\":\"c\",\"pla");
        Files.writeString(dir.resolve("chat-prefs.json"),
                "{\"format\":1,\"players\":[{\"player\":\""+a+"\",\"pref\":{\"auto\":true,\"language\":\"zh_tw\"}},{\"player\":\""+b+"\",\"pref\":{\"language\":\"klingon\"}}]}");
        try(Database db=Database.open(dir.resolve("g.db"),LOG)) {
            LegacyImport.run(db,dir,LOG);
            assertFalse(Files.exists(dir.resolve("records.jsonl"))); assertTrue(Files.exists(dir.resolve("records.jsonl.migrated")));
            assertTrue(Files.exists(dir.resolve("chat-prefs.json.migrated")));
            LegacyImport.run(db,dir,LOG); // nothing left to import
            RunLog log=new RunLog(db); log.load();
            assertEquals(new RunLog.Stats(2,1,1.0,300L),log.stats("c",0,a));
            Map<String,String> settings=db.call(c -> {
                Map<String,String> out=new TreeMap<>();
                try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT uuid,key,value FROM player_settings")) {
                    while(r.next()) out.put(r.getString(1).substring(0,4)+"/"+r.getString(2),r.getString(3));
                }
                return out;
            });
            String pa=a.toString().substring(0,4);
            assertEquals(Map.of(pa+"/translate_auto","true",pa+"/translate_lang","zh-TW"),settings,"Unsupported languages are dropped");
        }
    }
    @Test void settingsParseOnlyAllowedValues() {
        assertEquals(50,Settings.SOUND_SYSTEM.parse("50")); assertNull(Settings.SOUND_SYSTEM.parse("55")); assertNull(Settings.SOUND_SYSTEM.parse("x"));
        assertEquals(Boolean.TRUE,Settings.TRANSLATE_AUTO.parse("true")); assertNull(Settings.TRANSLATE_AUTO.parse("maybe"));
        assertEquals("ja",Settings.UI_LANG.parse("ja")); assertNull(Settings.UI_LANG.parse("ko"));
        assertEquals("zh-TW",Settings.TRANSLATE_LANG.parse("zh-TW")); assertEquals("ui",Settings.TRANSLATE_LANG.parse("ui"));
        assertSame(Settings.SOUND_COURSE,Settings.byKey("SOUND.COURSE"));
        assertEquals(Settings.ALL.size(),Settings.ALL.stream().map(Setting::key).distinct().count());
        assertFalse(Settings.PLAYERS_VISIBLE.menu());
    }
}
