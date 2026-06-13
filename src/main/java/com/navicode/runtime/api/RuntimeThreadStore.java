package com.navicode.runtime.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class RuntimeThreadStore implements AutoCloseable {
    private final Connection connection;

    public RuntimeThreadStore(Path dbPath) throws SQLException {
        try {
            Files.createDirectories(dbPath.getParent());
        } catch (Exception e) {
            throw new SQLException("无法创建 Runtime API 数据库目录: " + e.getMessage(), e);
        }
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        initTables();
    }

    public static Path defaultDbPath() {
        String configured = System.getProperty("navicode.runtime.dir");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("NAVICODE_RUNTIME_DIR");
        }
        if (configured == null || configured.isBlank()) {
            configured = Path.of(System.getProperty("user.home"), ".navicode", "runtime").toString();
        }
        return Path.of(configured).resolve("runtime.db");
    }

    public synchronized String createThread() {
        String id = "thread_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String now = Instant.now().toString();
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO runtime_threads (id, created_at, updated_at) VALUES (?, ?, ?)
                """)) {
            ps.setString(1, id);
            ps.setString(2, now);
            ps.setString(3, now);
            ps.executeUpdate();
            appendEvent(id, "thread.created", "{\"thread_id\":\"" + id + "\"}");
            return id;
        } catch (SQLException e) {
            throw new IllegalStateException("创建 runtime thread 失败: " + e.getMessage(), e);
        }
    }

    public synchronized void updateThreadTurn(String threadId, String turnId) {
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE runtime_threads SET last_turn_id = ?, updated_at = ? WHERE id = ?
                """)) {
            ps.setString(1, turnId);
            ps.setString(2, Instant.now().toString());
            ps.setString(3, threadId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update runtime thread: " + e.getMessage(), e);
        }
    }

    public synchronized void updateThreadCwd(String threadId, String cwd) {
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE runtime_threads SET cwd = ?, updated_at = ? WHERE id = ?
                """)) {
            ps.setString(1, cwd);
            ps.setString(2, Instant.now().toString());
            ps.setString(3, threadId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update runtime thread cwd: " + e.getMessage(), e);
        }
    }

    public synchronized boolean exists(String threadId) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM runtime_threads WHERE id = ?")) {
            ps.setString(1, threadId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取 runtime thread 失败: " + e.getMessage(), e);
        }
    }

    public synchronized long appendEvent(String threadId, String type, String data) {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO runtime_events (thread_id, type, data, created_at)
                VALUES (?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, threadId);
            ps.setString(2, type);
            ps.setString(3, data == null ? "{}" : data);
            ps.setString(4, Instant.now().toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("写入 runtime event 失败: " + e.getMessage(), e);
        }
    }

    public synchronized List<RuntimeEvent> events(String threadId, long afterId) {
        List<RuntimeEvent> events = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT id, thread_id, type, data, created_at FROM runtime_events
                WHERE thread_id = ? AND id > ?
                ORDER BY id ASC
                """)) {
            ps.setString(1, threadId);
            ps.setLong(2, afterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    events.add(new RuntimeEvent(
                            rs.getLong("id"),
                            rs.getString("thread_id"),
                            rs.getString("type"),
                            rs.getString("data"),
                            Instant.parse(rs.getString("created_at"))
                    ));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取 runtime events 失败: " + e.getMessage(), e);
        }
        return events;
    }

    private void initTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS runtime_threads (
                        id TEXT PRIMARY KEY,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        cwd TEXT,
                        last_turn_id TEXT
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS runtime_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        thread_id TEXT NOT NULL,
                        type TEXT NOT NULL,
                        data TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_runtime_events_thread ON runtime_events(thread_id, id)");
        }
        addColumnIfMissing("runtime_threads", "updated_at", "TEXT");
        addColumnIfMissing("runtime_threads", "cwd", "TEXT");
        addColumnIfMissing("runtime_threads", "last_turn_id", "TEXT");
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE runtime_threads SET updated_at = created_at
                WHERE updated_at IS NULL OR updated_at = ''
                """)) {
            ps.executeUpdate();
        }
    }

    private void addColumnIfMissing(String table, String column, String definition) throws SQLException {
        try (ResultSet rs = connection.getMetaData().getColumns(null, null, table, column)) {
            if (rs.next()) {
                return;
            }
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }
}
