package konqasasas.gauntlet.data;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * gauntlet.db (SQLite, WAL). One connection, used only by one worker thread, so
 * every statement runs in submission order and the main thread never waits on disk.
 */
public final class Database implements AutoCloseable {
    public interface Task<T> { T run(Connection connection) throws SQLException; }
    private static final int SCHEMA=4;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r -> { Thread t=new Thread(r,"Gauntlet-DB"); t.setDaemon(true); return t; });
    private final Logger logger;
    private Connection connection;

    private Database(Logger logger) { this.logger=logger; }
    public static Database open(Path file,Logger logger) throws IOException {
        Database db=new Database(logger);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            db.worker.submit(() -> { db.connect(file); return null; }).get();
            db.call(Database::migrate);
            return db;
        } catch(ExecutionException|InterruptedException|RuntimeException e) {
            db.close();
            if(e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IOException("Cannot open "+file,e instanceof ExecutionException ? e.getCause() : e);
        }
    }
    private void connect(Path file) throws Exception {
        // The driver comes from plugin.yml libraries; load it through this plugin's class loader.
        Driver driver=(Driver)Class.forName("org.sqlite.JDBC",true,Database.class.getClassLoader()).getDeclaredConstructor().newInstance();
        connection=driver.connect("jdbc:sqlite:"+file.toAbsolutePath(),new Properties());
        try(Statement s=connection.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA busy_timeout=5000");
        }
    }
    private static Void migrate(Connection c) throws SQLException {
        try(Statement s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS schema_version(version INTEGER NOT NULL)");
            int version=0;
            try(ResultSet r=s.executeQuery("SELECT MAX(version) FROM schema_version")) { if(r.next()) version=r.getInt(1); }
            if(version>SCHEMA) throw new SQLException("Database schema "+version+" is newer than this plugin ("+SCHEMA+")");
            c.setAutoCommit(false);
            try {
                if(version<1) {
                    s.execute("CREATE TABLE players(uuid TEXT PRIMARY KEY,name TEXT NOT NULL,first_seen INTEGER NOT NULL,last_seen INTEGER NOT NULL,playtime_ms INTEGER NOT NULL DEFAULT 0)");
                    s.execute("CREATE TABLE player_settings(uuid TEXT NOT NULL,key TEXT NOT NULL,value TEXT NOT NULL,PRIMARY KEY(uuid,key))");
                    s.execute("CREATE TABLE player_counters(uuid TEXT NOT NULL,key TEXT NOT NULL,value INTEGER NOT NULL,PRIMARY KEY(uuid,key))");
                    s.execute("CREATE TABLE runs(id TEXT PRIMARY KEY,course TEXT NOT NULL,rev INTEGER NOT NULL,player TEXT NOT NULL,result TEXT NOT NULL,"
                            +"reason TEXT,ticks INTEGER NOT NULL,wall_ms INTEGER,progress REAL,started_at INTEGER,ended_at INTEGER NOT NULL,"
                            +"x REAL,y REAL,z REAL,lane INTEGER,plugin TEXT)");
                    s.execute("CREATE INDEX runs_course ON runs(course,rev,result,ticks)");
                    s.execute("CREATE INDEX runs_player ON runs(player)");
                    s.execute("INSERT INTO schema_version(version) VALUES(1)");
                }
                if(version<2) {
                    // Courses of an era reached so far (stepping on the start), and where to resume after logging out.
                    s.execute("CREATE TABLE reached(player TEXT NOT NULL,course TEXT NOT NULL,first_at INTEGER NOT NULL,PRIMARY KEY(player,course))");
                    s.execute("CREATE TABLE resume(player TEXT PRIMARY KEY,course TEXT NOT NULL,at INTEGER NOT NULL)");
                    s.execute("INSERT INTO schema_version(version) VALUES(2)");
                }
                if(version<3) {
                    // Rank (Anulus) from beating era bosses; 0 for newcomers, so they have no row.
                    s.execute("CREATE TABLE ranks(player TEXT PRIMARY KEY,rank INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
                    s.execute("INSERT INTO schema_version(version) VALUES(3)");
                }
                if(version<4) {
                    // Cosmetics: what each player owns and uses, and every purchase (kept even when an item is taken back).
                    s.execute("CREATE TABLE owned(player TEXT NOT NULL,item TEXT NOT NULL,source TEXT NOT NULL,acquired_at INTEGER NOT NULL,PRIMARY KEY(player,item))");
                    s.execute("CREATE TABLE equipped(player TEXT NOT NULL,slot TEXT NOT NULL,item TEXT NOT NULL,PRIMARY KEY(player,slot))");
                    // buyer and recipient are kept apart so that gifts can come later.
                    s.execute("CREATE TABLE purchases(id TEXT PRIMARY KEY,buyer TEXT NOT NULL,recipient TEXT NOT NULL,bundle TEXT,created_at INTEGER NOT NULL,provider TEXT,provider_order TEXT)");
                    s.execute("CREATE INDEX purchases_buyer ON purchases(buyer)");
                    s.execute("CREATE INDEX purchases_recipient ON purchases(recipient)");
                    s.execute("CREATE TABLE purchase_items(purchase TEXT NOT NULL,item TEXT NOT NULL,PRIMARY KEY(purchase,item))");
                    // Rankings: records taken off the ranking (kept, with who/when/why, and can be put back),
                    // revisions that carry the records of the one before, and when each rank was first reached.
                    s.execute("ALTER TABLE runs ADD COLUMN voided_at INTEGER");
                    s.execute("ALTER TABLE runs ADD COLUMN voided_by TEXT");
                    s.execute("ALTER TABLE runs ADD COLUMN voided_reason TEXT");
                    s.execute("CREATE TABLE rev_carry(course TEXT NOT NULL,rev INTEGER NOT NULL,from_rev INTEGER NOT NULL,by TEXT,at INTEGER NOT NULL,PRIMARY KEY(course,rev))");
                    s.execute("CREATE TABLE rank_ups(player TEXT NOT NULL,rank INTEGER NOT NULL,at INTEGER NOT NULL,PRIMARY KEY(player,rank))");
                    // Replays of official clears (the files are in replays/; this is their index).
                    s.execute("CREATE TABLE replays(run TEXT PRIMARY KEY,player TEXT NOT NULL,course TEXT NOT NULL,rev INTEGER NOT NULL,ticks INTEGER NOT NULL,created_at INTEGER NOT NULL,bytes INTEGER NOT NULL)");
                    s.execute("CREATE INDEX replays_player ON replays(player,course,rev)");
                    s.execute("INSERT INTO schema_version(version) VALUES(4)");
                }
                c.commit();
            } catch(SQLException e) { c.rollback(); throw e; }
            finally { c.setAutoCommit(true); }
        }
        return null;
    }
    /** Queues work; the future fails with the SQLException. */
    public <T> CompletableFuture<T> submit(Task<T> task) {
        CompletableFuture<T> future=new CompletableFuture<>();
        try {
            worker.execute(() -> {
                try { future.complete(task.run(connection)); }
                catch(Throwable e) { future.completeExceptionally(e); }
            });
        } catch(RejectedExecutionException e) { future.completeExceptionally(e); }
        return future;
    }
    /** Fire-and-forget write; failures are logged. */
    public void write(String what,Task<?> task) {
        submit(task).whenComplete((v,e) -> { if(e!=null) logger.log(Level.SEVERE,"Database write failed: "+what,e); });
    }
    /** Runs and waits. Not for the main thread during play. */
    public <T> T call(Task<T> task) throws IOException {
        try { return submit(task).get(30,TimeUnit.SECONDS); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
        catch(ExecutionException|TimeoutException e) { throw new IOException(e.getCause()==null ? e : e.getCause()); }
    }
    /** Runs a task in one transaction. */
    public static <T> T transaction(Connection c,Task<T> task) throws SQLException {
        c.setAutoCommit(false);
        try { T result=task.run(c); c.commit(); return result; }
        catch(SQLException|RuntimeException e) { c.rollback(); throw e; }
        finally { c.setAutoCommit(true); }
    }
    /** Finishes queued writes, then closes. */
    @Override public void close() {
        worker.execute(() -> {
            try { if(connection!=null) connection.close(); }
            catch(SQLException e) { logger.log(Level.WARNING,"Closing the database failed",e); }
        });
        worker.shutdown();
        try { if(!worker.awaitTermination(30,TimeUnit.SECONDS)) logger.severe("Database writes did not finish within 30s"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
