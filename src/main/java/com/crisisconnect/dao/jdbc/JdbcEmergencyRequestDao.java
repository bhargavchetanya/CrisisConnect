package com.crisisconnect.dao.jdbc;

import com.crisisconnect.dao.EmergencyRequestDao;
import com.crisisconnect.db.JdbcTemplate;
import com.crisisconnect.model.DisasterType;
import com.crisisconnect.model.EmergencyRequest;
import com.crisisconnect.model.GeoLocation;
import com.crisisconnect.model.RequestStatus;
import com.crisisconnect.model.ResourceType;
import com.crisisconnect.model.UrgencyLevel;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public class JdbcEmergencyRequestDao implements EmergencyRequestDao {

    private final JdbcTemplate jdbc = new JdbcTemplate();

    private static EmergencyRequest map(ResultSet rs) throws SQLException {
        EmergencyRequest r = new EmergencyRequest();
        r.setId(rs.getInt("id"));
        r.setTitle(rs.getString("title"));
        r.setDisasterType(DisasterType.valueOf(rs.getString("disaster_type")));
        r.setResourceType(ResourceType.valueOf(rs.getString("resource_type")));
        r.setQuantityNeeded(rs.getInt("quantity_needed"));
        r.setQuantityFulfilled(rs.getInt("quantity_fulfilled"));
        r.setPeopleAffected(rs.getInt("people_affected"));
        r.setUrgency(UrgencyLevel.valueOf(rs.getString("urgency")));
        r.setLocation(new GeoLocation(rs.getString("city"), rs.getDouble("latitude"), rs.getDouble("longitude")));
        r.setStatus(RequestStatus.valueOf(rs.getString("status")));
        r.setContactPhone(rs.getString("contact_phone"));
        r.setVulnerableGroups(rs.getInt("vulnerable_groups") == 1);
        r.setSource(rs.getString("source"));
        r.setCreatedAt(LocalDateTime.parse(rs.getString("created_at")));
        return r;
    }

    @Override
    public EmergencyRequest save(EmergencyRequest r) {
        int id = jdbc.insert("""
                INSERT INTO emergency_requests(title, disaster_type, resource_type, quantity_needed, quantity_fulfilled,
                    people_affected, urgency, city, latitude, longitude, status, contact_phone, vulnerable_groups,
                    source, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                r.getTitle(), r.getDisasterType().name(), r.getResourceType().name(), r.getQuantityNeeded(),
                r.getQuantityFulfilled(), r.getPeopleAffected(), r.getUrgency().name(), r.getCity(),
                r.getLocation().getLatitude(), r.getLocation().getLongitude(), r.getStatus().name(),
                r.getContactPhone(), r.isVulnerableGroups() ? 1 : 0, r.getSource(), r.getCreatedAt().toString());
        r.setId(id);
        return r;
    }

    @Override
    public boolean update(EmergencyRequest r) {
        return jdbc.update("""
                UPDATE emergency_requests SET title=?, disaster_type=?, resource_type=?, quantity_needed=?,
                    quantity_fulfilled=?, people_affected=?, urgency=?, city=?, latitude=?, longitude=?, status=?,
                    contact_phone=?, vulnerable_groups=? WHERE id=?""",
                r.getTitle(), r.getDisasterType().name(), r.getResourceType().name(), r.getQuantityNeeded(),
                r.getQuantityFulfilled(), r.getPeopleAffected(), r.getUrgency().name(), r.getCity(),
                r.getLocation().getLatitude(), r.getLocation().getLongitude(), r.getStatus().name(),
                r.getContactPhone(), r.isVulnerableGroups() ? 1 : 0, r.getId()) == 1;
    }

    @Override
    public boolean updateProgress(int requestId, int quantityFulfilled, RequestStatus status) {
        return jdbc.update("UPDATE emergency_requests SET quantity_fulfilled=?, status=? WHERE id=?",
                quantityFulfilled, status.name(), requestId) == 1;
    }

    @Override
    public boolean delete(Integer id) {
        return jdbc.update("DELETE FROM emergency_requests WHERE id=?", id) == 1;
    }

    @Override
    public Optional<EmergencyRequest> findById(Integer id) {
        return jdbc.queryOne("SELECT * FROM emergency_requests WHERE id=?", JdbcEmergencyRequestDao::map, id);
    }

    @Override
    public List<EmergencyRequest> findAll() {
        return jdbc.query("SELECT * FROM emergency_requests ORDER BY created_at DESC", JdbcEmergencyRequestDao::map);
    }

    @Override
    public List<EmergencyRequest> findActive() {
        return jdbc.query("SELECT * FROM emergency_requests WHERE status IN ('OPEN','PARTIALLY_FULFILLED')",
                JdbcEmergencyRequestDao::map);
    }

    @Override
    public List<EmergencyRequest> findByStatus(RequestStatus status) {
        return jdbc.query("SELECT * FROM emergency_requests WHERE status=?", JdbcEmergencyRequestDao::map,
                status.name());
    }

    @Override
    public int count() {
        return jdbc.queryInt("SELECT COUNT(*) FROM emergency_requests");
    }
}
