package cn.swapy;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public final class Store implements AutoCloseable {
    @FunctionalInterface public interface SqlFunction<T> { T apply(Connection connection) throws Exception; }
    private final String url;
    private final ReentrantLock writer = new ReentrantLock(true);
    private final FileChannel lockChannel;
    private final FileLock lock;
    private Connection keeper;
    public final Path data;

    public Store(Path directory) {
        try {
            Files.createDirectories(directory);
            data = directory.toRealPath();
            lockChannel = FileChannel.open(data.resolve("service.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
            if (lock == null) { lockChannel.close(); throw new Problem("INSTANCE_ALREADY_RUNNING"); }
            url = "jdbc:sqlite:" + data.resolve("swapy.db");
            try (Connection c = connect()) {
                try (Statement pragma=c.createStatement();ResultSet result=pragma.executeQuery("PRAGMA journal_mode=WAL")) {
                    if(!result.next() || !"wal".equalsIgnoreCase(result.getString(1))) throw new Problem("WAL_UNAVAILABLE");
                }
                migrate(c);
            }
            keeper=connect();
        } catch (Exception e) { throw failure(e); }
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement s=c.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON"); s.execute("PRAGMA busy_timeout=10000"); s.execute("PRAGMA synchronous=FULL");
        }
        return c;
    }
    public <T> T read(SqlFunction<T> action) {
        try (Connection c=connect()) { return action.apply(c); }
        catch (Exception e) { throw failure(e); }
    }
    public <T> T write(SqlFunction<T> action) {
        writer.lock();
        try (Connection c=connect()) {
            c.setAutoCommit(false);
            try { T result=action.apply(c); c.commit(); return result; }
            catch (Exception e) { c.rollback(); throw e; }
        } catch (Exception e) { throw failure(e); }
        finally { writer.unlock(); }
    }
    private void migrate(Connection c) throws SQLException {
        String[] schema = {
            "CREATE TABLE IF NOT EXISTS metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS word_entries(id TEXT PRIMARY KEY,kind TEXT NOT NULL,payload TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS catalog_releases(version INTEGER PRIMARY KEY,lineage TEXT NOT NULL,payload TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS word_events(id INTEGER PRIMARY KEY,entry_id TEXT NOT NULL,version INTEGER NOT NULL,event TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS tasks(id TEXT PRIMARY KEY,kind TEXT NOT NULL,status TEXT NOT NULL,phase TEXT NOT NULL,payload TEXT NOT NULL,created_at TEXT NOT NULL,updated_at TEXT NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS task_idempotency ON tasks(json_extract(payload,'$.idempotencyKey')) WHERE json_extract(payload,'$.idempotencyKey') IS NOT NULL",
            "CREATE TABLE IF NOT EXISTS work_items(id TEXT PRIMARY KEY,task_id TEXT NOT NULL REFERENCES tasks(id),path TEXT NOT NULL,state TEXT NOT NULL,source_hash TEXT,size INTEGER,mtime INTEGER,payload TEXT NOT NULL DEFAULT '{}',UNIQUE(task_id,path))",
            "CREATE INDEX IF NOT EXISTS work_queue ON work_items(task_id,state)",
            "CREATE TABLE IF NOT EXISTS task_mappings(task_id TEXT NOT NULL REFERENCES tasks(id),source_id TEXT NOT NULL,target_id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(task_id,source_id),UNIQUE(task_id,target_id))",
            "CREATE TABLE IF NOT EXISTS issues(id TEXT PRIMARY KEY,task_id TEXT NOT NULL REFERENCES tasks(id),file_id TEXT NOT NULL,code TEXT NOT NULL,payload TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS task_issues ON issues(task_id,file_id)",
            "CREATE TABLE IF NOT EXISTS artifacts(id TEXT PRIMARY KEY,task_id TEXT NOT NULL REFERENCES tasks(id),file_id TEXT NOT NULL,path TEXT NOT NULL,staging TEXT NOT NULL,hash TEXT NOT NULL,state TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS path_mappings(task_id TEXT NOT NULL REFERENCES tasks(id),source_path TEXT NOT NULL,target_path TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(task_id,source_path),UNIQUE(task_id,target_path))",
            "CREATE TABLE IF NOT EXISTS work_groups(id TEXT PRIMARY KEY,task_id TEXT NOT NULL REFERENCES tasks(id),state TEXT NOT NULL,generation INTEGER NOT NULL DEFAULT 1)",
            "CREATE TABLE IF NOT EXISTS group_members(group_id TEXT NOT NULL REFERENCES work_groups(id),work_id TEXT NOT NULL REFERENCES work_items(id),PRIMARY KEY(group_id,work_id))",
            "CREATE TABLE IF NOT EXISTS import_batches(id TEXT PRIMARY KEY,payload TEXT NOT NULL,state TEXT NOT NULL)",
            "INSERT OR IGNORE INTO metadata(key,value) VALUES('schema_version','1')"
        };
        try (Statement s=c.createStatement()) { for (String sql:schema) s.execute(sql); }
    }
    public static int execute(Connection c,String sql,Object... values) throws SQLException {
        try (PreparedStatement p=c.prepareStatement(sql)) { bind(p,values); return p.executeUpdate(); }
    }
    public static List<Map<String,Object>> rows(Connection c,String sql,Object... values) throws SQLException {
        try (PreparedStatement p=c.prepareStatement(sql)) {
            bind(p,values);
            try (ResultSet r=p.executeQuery()) {
                var result=new ArrayList<Map<String,Object>>();
                while(r.next()) {
                    var row=new LinkedHashMap<String,Object>();
                    for(int i=1;i<=r.getMetaData().getColumnCount();i++) row.put(r.getMetaData().getColumnLabel(i),r.getObject(i));
                    result.add(row);
                }
                return result;
            }
        }
    }
    private static void bind(PreparedStatement p,Object[] values) throws SQLException {
        for(int i=0;i<values.length;i++) p.setObject(i+1,values[i]);
    }
    private static RuntimeException failure(Exception e) {
        return e instanceof RuntimeException runtime ? runtime : new Problem("STORAGE_ERROR",e.getMessage());
    }
    @Override public void close() {
        writer.lock();
        try { if(keeper!=null)keeper.close();lock.release(); lockChannel.close(); }
        catch(Exception e) { throw failure(e); }
        finally { writer.unlock(); }
    }
}
