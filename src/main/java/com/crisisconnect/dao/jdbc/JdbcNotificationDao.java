package com.crisisconnect.dao.jdbc;

import com.crisisconnect.dao.NotificationDao;
import com.crisisconnect.db.JdbcTemplate;
import com.crisisconnect.model.Notification;
import com.crisisconnect.model.NotificationChannel;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

public class JdbcNotificationDao implements NotificationDao {

    private final JdbcTemplate jdbc = new JdbcTemplate();

    private static Notification map(ResultSet rs) throws SQLException {
        Notification n = new Notification();
        n.setId(rs.getInt("id"));
        n.setChannel(NotificationChannel.valueOf(rs.getString("channel")));
        n.setRecipient(rs.getString("recipient"));
        n.setRecipientName(rs.getString("recipient_name"));
        n.setMessage(rs.getString("message"));
        n.setEvent(rs.getString("event"));
        int req = rs.getInt("request_id");
        n.setRequestId(rs.wasNull() ? null : req);
        n.setStatus(Notification.Status.valueOf(rs.getString("status")));
        n.setCreatedAt(LocalDateTime.parse(rs.getString("created_at")));
        n.setError(rs.getString("error"));
        return n;
    }

    @Override
    public Notification save(Notification n) {
        int id = jdbc.insert("""
                INSERT INTO notifications(channel, recipient, recipient_name, message, event, request_id, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                n.getChannel().name(), n.getRecipient(), n.getRecipientName(), n.getMessage(), n.getEvent(),
                n.getRequestId(), n.getStatus().name(), n.getCreatedAt().toString());
        n.setId(id);
        return n;
    }

    @Override
    public List<Notification> findRecent(NotificationChannel channel, int limit) {
        if (channel == null) {
            return jdbc.query("SELECT * FROM notifications ORDER BY id DESC LIMIT ?", JdbcNotificationDao::map, limit);
        }
        return jdbc.query("SELECT * FROM notifications WHERE channel=? ORDER BY id DESC LIMIT ?",
                JdbcNotificationDao::map, channel.name(), limit);
    }

    @Override
    public List<Notification> findByRequest(int requestId) {
        return jdbc.query("SELECT * FROM notifications WHERE request_id=? ORDER BY id", JdbcNotificationDao::map, requestId);
    }

    @Override
    public void updateStatus(int id, Notification.Status status, String error) {
        jdbc.update("UPDATE notifications SET status=?, error=? WHERE id=?", status.name(),
                error == null ? null : error.length() > 300 ? error.substring(0, 300) : error, id);
    }

    @Override
    public int count() {
        return jdbc.queryInt("SELECT COUNT(*) FROM notifications");
    }
}
