package com.crisisconnect.dao.jdbc;

import com.crisisconnect.dao.AuditDao;
import com.crisisconnect.db.JdbcTemplate;
import com.crisisconnect.model.AuditEntry;

import java.time.LocalDateTime;
import java.util.List;

public class JdbcAuditDao implements AuditDao {

    private final JdbcTemplate jdbc = new JdbcTemplate();

    @Override
    public void save(AuditEntry e) {
        int id = jdbc.insert("INSERT INTO audit_log(actor, action, details, ip, created_at) VALUES (?, ?, ?, ?, ?)",
                e.getActor(), e.getAction(), e.getDetails(), e.getIp(), e.getCreatedAt().toString());
        e.setId(id);
    }

    @Override
    public List<AuditEntry> findRecent(int limit) {
        return jdbc.query("SELECT * FROM audit_log ORDER BY id DESC LIMIT ?", rs -> {
            AuditEntry e = new AuditEntry(rs.getString("actor"), rs.getString("action"), rs.getString("details"),
                    rs.getString("ip"));
            e.setId(rs.getInt("id"));
            e.setCreatedAt(LocalDateTime.parse(rs.getString("created_at")));
            return e;
        }, limit);
    }
}
