package com.crisisconnect.db;

import com.crisisconnect.exception.DataAccessException;
import com.crisisconnect.util.AppConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * Central place that knows how to open JDBC connections (Unit 4 - JDBC architecture).
 *
 * Application -> DAO -> JDBC API (java.sql) -> DriverManager -> SQLite JDBC Driver -> crisisconnect.db
 */
public final class DatabaseManager {

    private static final Logger LOG = Logger.getLogger(DatabaseManager.class.getName());
    private static volatile DatabaseManager instance;

    private final String url;

    private DatabaseManager(String url) {
        this.url = url;
    }

    /** Thread-safe lazy singleton (double-checked locking). */
    public static DatabaseManager getInstance() {
        if (instance == null) {
            synchronized (DatabaseManager.class) {
                if (instance == null) {
                    instance = new DatabaseManager(
                            AppConfig.get().getString("db.url", "jdbc:sqlite:data/crisisconnect.db"));
                }
            }
        }
        return instance;
    }

    /** Used by unit tests to point at a temporary database. */
    public static synchronized void overrideUrl(String url) {
        instance = new DatabaseManager(url);
    }

    public Connection getConnection() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys = ON");
            s.execute("PRAGMA busy_timeout = 5000");
        }
        return c;
    }

    /** Creates the data folder and runs schema.sql (idempotent - uses IF NOT EXISTS). */
    public void initSchema() {
        try {
            if (url.startsWith("jdbc:sqlite:") && !url.contains(":memory:")) {
                Path dbFile = Path.of(url.substring("jdbc:sqlite:".length()));
                if (dbFile.getParent() != null) Files.createDirectories(dbFile.getParent());
            }
            String script = readResource("/schema.sql");
            try (Connection c = getConnection(); Statement st = c.createStatement()) {
                for (String sql : stripComments(script).split(";")) {
                    if (!sql.isBlank()) st.execute(sql.trim());
                }
                migrate(c);
            }
            LOG.info("Database schema ready at " + url);
        } catch (SQLException | IOException e) {
            throw new DataAccessException("Could not initialise database schema", e);
        }
    }

    /**
     * Small, idempotent schema migrations for databases created by an older version.
     * v2: emergency_requests.tracking_code (public SOS tracking ID, e.g. CC-7KQ4MZ).
     */
    private static void migrate(Connection c) throws SQLException {
        boolean hasTracking = false;
        try (Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery("PRAGMA table_info(emergency_requests)")) {
            while (rs.next()) if ("tracking_code".equalsIgnoreCase(rs.getString("name"))) hasTracking = true;
        }
        if (!hasTracking) {
            try (Statement st = c.createStatement()) {
                st.execute("ALTER TABLE emergency_requests ADD COLUMN tracking_code TEXT");
            }
            LOG.info("Migration: added emergency_requests.tracking_code");
        }
        // back-fill codes for rows created before tracking existed
        java.util.List<Integer> missing = new java.util.ArrayList<>();
        try (Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT id FROM emergency_requests WHERE tracking_code IS NULL")) {
            while (rs.next()) missing.add(rs.getInt(1));
        }
        try (java.sql.PreparedStatement ps = c.prepareStatement("UPDATE emergency_requests SET tracking_code=? WHERE id=?")) {
            for (int id : missing) {
                ps.setString(1, com.crisisconnect.util.TrackingCodes.next());
                ps.setInt(2, id);
                ps.executeUpdate();
            }
        }
        try (Statement st = c.createStatement()) {
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_requests_tracking ON emergency_requests(tracking_code)");
        }
        // v3: gateway error text for real WhatsApp / e-mail deliveries
        boolean hasError = false;
        try (Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery("PRAGMA table_info(notifications)")) {
            while (rs.next()) if ("error".equalsIgnoreCase(rs.getString("name"))) hasError = true;
        }
        if (!hasError) {
            try (Statement st = c.createStatement()) { st.execute("ALTER TABLE notifications ADD COLUMN error TEXT"); }
        }
    }

    /** Character-stream reading of a classpath file (Unit 3 - I/O). */
    private static String readResource(String name) throws IOException {
        InputStream in = DatabaseManager.class.getResourceAsStream(name);
        if (in == null) throw new IOException("Missing resource " + name);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String stripComments(String sql) {
        StringBuilder sb = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (!line.trim().startsWith("--")) sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    public String getUrl() { return url; }
}
