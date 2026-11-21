package com.crisisconnect.dao.jdbc;

import com.crisisconnect.dao.UserDao;
import com.crisisconnect.db.JdbcTemplate;
import com.crisisconnect.model.Role;
import com.crisisconnect.model.User;
import com.crisisconnect.model.UserStatus;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public class JdbcUserDao implements UserDao {

    private static final String SELECT = """
            SELECT u.*, o.name AS org_name
            FROM users u LEFT JOIN organizations o ON o.id = u.organization_id
            """;

    private final JdbcTemplate jdbc = new JdbcTemplate();

    private static LocalDateTime time(ResultSet rs, String col) throws SQLException {
        String v = rs.getString(col);
        return v == null ? null : LocalDateTime.parse(v);
    }

    private static User map(ResultSet rs) throws SQLException {
        User u = new User(rs.getString("email"), rs.getString("name"), rs.getString("password_hash"));
        u.setId(rs.getInt("id"));
        u.setRole(Role.valueOf(rs.getString("role")));
        u.setStatus(UserStatus.valueOf(rs.getString("status")));
        int org = rs.getInt("organization_id");
        u.setOrganizationId(rs.wasNull() ? null : org);
        u.setOrganizationName(rs.getString("org_name"));
        u.setEmailVerified(rs.getInt("email_verified") == 1);
        u.setFailedLogins(rs.getInt("failed_logins"));
        u.setLockUntil(time(rs, "lock_until"));
        u.setLastLoginAt(time(rs, "last_login_at"));
        u.setCreatedAt(time(rs, "created_at"));
        return u;
    }

    private static String str(LocalDateTime t) { return t == null ? null : t.toString(); }

    @Override
    public User save(User u) {
        int id = jdbc.insert("""
                INSERT INTO users(email, name, password_hash, role, status, organization_id, email_verified,
                                  failed_logins, lock_until, last_login_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                u.getEmail(), u.getName(), u.getPasswordHash(), u.getRole().name(), u.getStatus().name(),
                u.getOrganizationId(), u.isEmailVerified() ? 1 : 0, u.getFailedLogins(), str(u.getLockUntil()),
                str(u.getLastLoginAt()), u.getCreatedAt().toString());
        u.setId(id);
        return u;
    }

    @Override
    public boolean update(User u) {
        return jdbc.update("""
                UPDATE users SET name=?, role=?, status=?, organization_id=?, email_verified=? WHERE id=?""",
                u.getName(), u.getRole().name(), u.getStatus().name(), u.getOrganizationId(),
                u.isEmailVerified() ? 1 : 0, u.getId()) == 1;
    }

    @Override
    public boolean updateLoginState(User u) {
        return jdbc.update("UPDATE users SET failed_logins=?, lock_until=?, last_login_at=? WHERE id=?",
                u.getFailedLogins(), str(u.getLockUntil()), str(u.getLastLoginAt()), u.getId()) == 1;
    }

    @Override
    public boolean updatePassword(int userId, String passwordHash) {
        return jdbc.update("UPDATE users SET password_hash=? WHERE id=?", passwordHash, userId) == 1;
    }

    @Override
    public boolean delete(Integer id) {
        return jdbc.update("DELETE FROM users WHERE id=?", id) == 1;
    }

    @Override
    public Optional<User> findById(Integer id) {
        return jdbc.queryOne(SELECT + " WHERE u.id=?", JdbcUserDao::map, id);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return jdbc.queryOne(SELECT + " WHERE u.email=?", JdbcUserDao::map, email);
    }

    @Override
    public List<User> findAll() {
        return jdbc.query(SELECT + " ORDER BY CASE u.status WHEN 'PENDING' THEN 0 ELSE 1 END, u.created_at DESC",
                JdbcUserDao::map);
    }

    @Override
    public List<User> findPending() {
        return jdbc.query(SELECT + " WHERE u.status='PENDING' AND u.email_verified=1 ORDER BY u.created_at",
                JdbcUserDao::map);
    }

    @Override
    public int count() {
        return jdbc.queryInt("SELECT COUNT(*) FROM users");
    }

    // ------------------------------------------------------------------ sessions
    @Override
    public void createSession(String tokenHash, int userId, LocalDateTime expiresAt, String userAgent) {
        jdbc.update("INSERT INTO sessions(token_hash, user_id, expires_at, user_agent, created_at) VALUES (?, ?, ?, ?, ?)",
                tokenHash, userId, expiresAt.toString(), userAgent, LocalDateTime.now().toString());
    }

    @Override
    public Optional<User> findBySessionHash(String tokenHash) {
        return jdbc.queryOne(SELECT + " JOIN sessions s ON s.user_id = u.id WHERE s.token_hash=? AND s.expires_at > ?",
                JdbcUserDao::map, tokenHash, LocalDateTime.now().toString());
    }

    @Override
    public void deleteSession(String tokenHash) {
        jdbc.update("DELETE FROM sessions WHERE token_hash=?", tokenHash);
    }

    @Override
    public void deleteSessionsFor(int userId) {
        jdbc.update("DELETE FROM sessions WHERE user_id=?", userId);
    }

    @Override
    public int purgeExpiredSessions() {
        return jdbc.update("DELETE FROM sessions WHERE expires_at <= ?", LocalDateTime.now().toString());
    }

    // ------------------------------------------------------------------ e-mail codes
    @Override
    public void saveEmailCode(int userId, String codeHash, LocalDateTime expiresAt) {
        jdbc.update("INSERT INTO email_codes(user_id, code_hash, expires_at, attempts, created_at) VALUES (?, ?, ?, 0, ?)",
                userId, codeHash, expiresAt.toString(), LocalDateTime.now().toString());
    }

    @Override
    public Optional<EmailCode> latestEmailCode(int userId) {
        return jdbc.queryOne("SELECT * FROM email_codes WHERE user_id=? ORDER BY id DESC LIMIT 1",
                rs -> new EmailCode(rs.getInt("id"), rs.getString("code_hash"), LocalDateTime.parse(rs.getString("expires_at")),
                        rs.getInt("attempts"), LocalDateTime.parse(rs.getString("created_at"))), userId);
    }

    @Override
    public void incrementCodeAttempts(int codeId) {
        jdbc.update("UPDATE email_codes SET attempts = attempts + 1 WHERE id=?", codeId);
    }

    @Override
    public void deleteEmailCodes(int userId) {
        jdbc.update("DELETE FROM email_codes WHERE user_id=?", userId);
    }
}
