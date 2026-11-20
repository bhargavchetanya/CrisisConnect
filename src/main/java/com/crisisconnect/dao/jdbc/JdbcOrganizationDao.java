package com.crisisconnect.dao.jdbc;

import com.crisisconnect.dao.OrganizationDao;
import com.crisisconnect.db.JdbcTemplate;
import com.crisisconnect.model.GeoLocation;
import com.crisisconnect.model.Organization;
import com.crisisconnect.model.OrganizationType;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public class JdbcOrganizationDao implements OrganizationDao {

    private final JdbcTemplate jdbc = new JdbcTemplate();

    private static Organization map(ResultSet rs) throws SQLException {
        Organization o = new Organization();
        o.setId(rs.getInt("id"));
        o.setName(rs.getString("name"));
        o.setType(OrganizationType.valueOf(rs.getString("type")));
        o.setContactPhone(rs.getString("contact_phone"));
        o.setEmail(rs.getString("email"));
        o.setLocation(new GeoLocation(rs.getString("city"), rs.getDouble("latitude"), rs.getDouble("longitude")));
        o.setCreatedAt(LocalDateTime.parse(rs.getString("created_at")));
        return o;
    }

    @Override
    public Organization save(Organization o) {
        int id = jdbc.insert("""
                INSERT INTO organizations(name, type, contact_phone, email, city, latitude, longitude, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                o.getName(), o.getType().name(), o.getContactPhone(), o.getEmail(),
                o.getCity(), o.getLocation().getLatitude(), o.getLocation().getLongitude(),
                o.getCreatedAt().toString());
        o.setId(id);
        return o;
    }

    @Override
    public boolean update(Organization o) {
        return jdbc.update("""
                UPDATE organizations SET name=?, type=?, contact_phone=?, email=?, city=?, latitude=?, longitude=?
                WHERE id=?""",
                o.getName(), o.getType().name(), o.getContactPhone(), o.getEmail(), o.getCity(),
                o.getLocation().getLatitude(), o.getLocation().getLongitude(), o.getId()) == 1;
    }

    @Override
    public boolean delete(Integer id) {
        return jdbc.update("DELETE FROM organizations WHERE id=?", id) == 1;
    }

    @Override
    public Optional<Organization> findById(Integer id) {
        return jdbc.queryOne("SELECT * FROM organizations WHERE id=?", JdbcOrganizationDao::map, id);
    }

    @Override
    public Optional<Organization> findByName(String name) {
        return jdbc.queryOne("SELECT * FROM organizations WHERE lower(name)=lower(?)", JdbcOrganizationDao::map, name);
    }

    @Override
    public List<Organization> findAll() {
        return jdbc.query("SELECT * FROM organizations ORDER BY name", JdbcOrganizationDao::map);
    }

    @Override
    public int count() {
        return jdbc.queryInt("SELECT COUNT(*) FROM organizations");
    }
}
