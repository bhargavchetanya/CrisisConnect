package com.crisisconnect.dao.jdbc;

import com.crisisconnect.dao.ResourceDao;
import com.crisisconnect.db.JdbcTemplate;
import com.crisisconnect.model.GeoLocation;
import com.crisisconnect.model.Resource;
import com.crisisconnect.model.ResourceFactory;
import com.crisisconnect.model.ResourceType;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public class JdbcResourceDao implements ResourceDao {

    private static final String SELECT = """
            SELECT r.*, o.name AS org_name
            FROM resources r JOIN organizations o ON o.id = r.organization_id
            """;

    private final JdbcTemplate jdbc = new JdbcTemplate();

    /** Uses the factory so each row becomes the right subclass (FoodResource, ShelterResource ...). */
    private static Resource map(ResultSet rs) throws SQLException {
        Resource r = ResourceFactory.create(ResourceType.valueOf(rs.getString("type")));
        r.setId(rs.getInt("id"));
        r.setOrganizationId(rs.getInt("organization_id"));
        r.setOrganizationName(rs.getString("org_name"));
        r.setName(rs.getString("name"));
        r.setAvailableQuantity(rs.getInt("available_quantity"));
        r.setTotalCapacity(rs.getInt("total_capacity"));
        r.setLocation(new GeoLocation(rs.getString("city"), rs.getDouble("latitude"), rs.getDouble("longitude")));
        String expiry = rs.getString("expiry_date");
        r.setExpiryDate(expiry == null ? null : LocalDate.parse(expiry));
        r.setCreatedAt(LocalDateTime.parse(rs.getString("created_at")));
        return r;
    }

    @Override
    public Resource save(Resource r) {
        int id = jdbc.insert("""
                INSERT INTO resources(organization_id, type, name, available_quantity, total_capacity,
                                      city, latitude, longitude, expiry_date, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                r.getOrganizationId(), r.getType().name(), r.getName(), r.getAvailableQuantity(),
                r.getTotalCapacity(), r.getCity(), r.getLocation().getLatitude(), r.getLocation().getLongitude(),
                r.getExpiryDate().map(LocalDate::toString).orElse(null), r.getCreatedAt().toString());
        r.setId(id);
        return r;
    }

    @Override
    public boolean update(Resource r) {
        return jdbc.update("""
                UPDATE resources SET organization_id=?, type=?, name=?, available_quantity=?, total_capacity=?,
                       city=?, latitude=?, longitude=?, expiry_date=? WHERE id=?""",
                r.getOrganizationId(), r.getType().name(), r.getName(), r.getAvailableQuantity(),
                r.getTotalCapacity(), r.getCity(), r.getLocation().getLatitude(), r.getLocation().getLongitude(),
                r.getExpiryDate().map(LocalDate::toString).orElse(null), r.getId()) == 1;
    }

    @Override
    public boolean updateQuantity(int resourceId, int newAvailableQuantity) {
        return jdbc.update("UPDATE resources SET available_quantity=? WHERE id=?",
                newAvailableQuantity, resourceId) == 1;
    }

    @Override
    public boolean delete(Integer id) {
        return jdbc.update("DELETE FROM resources WHERE id=?", id) == 1;
    }

    @Override
    public Optional<Resource> findById(Integer id) {
        return jdbc.queryOne(SELECT + " WHERE r.id=?", JdbcResourceDao::map, id);
    }

    @Override
    public List<Resource> findAll() {
        return jdbc.query(SELECT + " ORDER BY r.type, r.name", JdbcResourceDao::map);
    }

    @Override
    public List<Resource> findByType(ResourceType type) {
        return jdbc.query(SELECT + " WHERE r.type=? ORDER BY r.name", JdbcResourceDao::map, type.name());
    }

    @Override
    public List<Resource> findAvailable() {
        return jdbc.query(SELECT + " WHERE r.available_quantity > 0", JdbcResourceDao::map);
    }

    @Override
    public int count() {
        return jdbc.queryInt("SELECT COUNT(*) FROM resources");
    }
}
