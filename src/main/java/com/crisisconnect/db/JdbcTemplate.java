package com.crisisconnect.db;

import com.crisisconnect.exception.DataAccessException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Small reusable helper that removes JDBC boiler-plate from DAOs.
 * - Always uses PreparedStatement (prevents SQL injection)
 * - Joins the current transaction if one is open, otherwise opens/closes its own connection
 * - Converts SQLException into the unchecked DataAccessException
 */
public final class JdbcTemplate {

    /** Maps one ResultSet row to an object (generic functional interface). */
    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    @FunctionalInterface
    private interface ConnectionCallback<R> {
        R doWith(Connection c) throws SQLException;
    }

    private <R> R execute(ConnectionCallback<R> cb) {
        Connection tx = TransactionManager.current();
        try {
            if (tx != null) return cb.doWith(tx);
            try (Connection c = DatabaseManager.getInstance().getConnection()) {
                return cb.doWith(c);
            }
        } catch (SQLException e) {
            throw new DataAccessException("Database error: " + e.getMessage(), e);
        }
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
    }

    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        return execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    List<T> out = new ArrayList<>();
                    while (rs.next()) out.add(mapper.map(rs));
                    return out;
                }
            }
        });
    }

    public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        List<T> list = query(sql, mapper, params);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public int queryInt(String sql, Object... params) {
        return queryOne(sql, rs -> rs.getInt(1), params).orElse(0);
    }

    /** INSERT / UPDATE / DELETE - returns affected rows. */
    public int update(String sql, Object... params) {
        return execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, params);
                return ps.executeUpdate();
            }
        });
    }

    /** INSERT returning the auto-generated primary key. */
    public int insert(String sql, Object... params) {
        return execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bind(ps, params);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) return keys.getInt(1);
                }
                throw new SQLException("No generated key returned");
            }
        });
    }
}
