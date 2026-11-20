package com.crisisconnect.dao.jdbc;

import com.crisisconnect.dao.AllocationDao;
import com.crisisconnect.db.JdbcTemplate;
import com.crisisconnect.model.Allocation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public class JdbcAllocationDao implements AllocationDao {

    /** 3-table JOIN so the UI can show human readable names. */
    private static final String SELECT = """
            SELECT a.*, er.title AS request_title, er.city AS city, r.name AS resource_name, o.name AS org_name
            FROM allocations a
            JOIN emergency_requests er ON er.id = a.request_id
            JOIN resources r          ON r.id  = a.resource_id
            JOIN organizations o      ON o.id  = r.organization_id
            """;

    private final JdbcTemplate jdbc = new JdbcTemplate();

    private static Allocation map(ResultSet rs) throws SQLException {
        Allocation a = new Allocation();
        a.setId(rs.getInt("id"));
        a.setRequestId(rs.getInt("request_id"));
        a.setResourceId(rs.getInt("resource_id"));
        a.setQuantity(rs.getInt("quantity"));
        a.setDistanceKm(rs.getDouble("distance_km"));
        a.setCreatedAt(LocalDateTime.parse(rs.getString("created_at")));
        a.setRequestTitle(rs.getString("request_title"));
        a.setResourceName(rs.getString("resource_name"));
        a.setOrganizationName(rs.getString("org_name"));
        a.setCity(rs.getString("city"));
        return a;
    }

    @Override
    public Allocation save(Allocation a) {
        int id = jdbc.insert("""
                INSERT INTO allocations(request_id, resource_id, quantity, distance_km, created_at)
                VALUES (?, ?, ?, ?, ?)""",
                a.getRequestId(), a.getResourceId(), a.getQuantity(), a.getDistanceKm(), a.getCreatedAt().toString());
        a.setId(id);
        return a;
    }

    @Override
    public boolean update(Allocation a) {
        return jdbc.update("UPDATE allocations SET quantity=?, distance_km=? WHERE id=?",
                a.getQuantity(), a.getDistanceKm(), a.getId()) == 1;
    }

    @Override
    public boolean delete(Integer id) {
        return jdbc.update("DELETE FROM allocations WHERE id=?", id) == 1;
    }

    @Override
    public Optional<Allocation> findById(Integer id) {
        return jdbc.queryOne(SELECT + " WHERE a.id=?", JdbcAllocationDao::map, id);
    }

    @Override
    public List<Allocation> findAll() {
        return jdbc.query(SELECT + " ORDER BY a.created_at DESC, a.id DESC", JdbcAllocationDao::map);
    }

    @Override
    public List<Allocation> findByRequest(int requestId) {
        return jdbc.query(SELECT + " WHERE a.request_id=? ORDER BY a.id", JdbcAllocationDao::map, requestId);
    }

    @Override
    public List<Allocation> findRecent(int limit) {
        return jdbc.query(SELECT + " ORDER BY a.id DESC LIMIT ?", JdbcAllocationDao::map, limit);
    }

    @Override
    public int count() {
        return jdbc.queryInt("SELECT COUNT(*) FROM allocations");
    }
}
