package com.crisisconnect.service;

import com.crisisconnect.dao.AuditDao;
import com.crisisconnect.model.AuditEntry;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Append-only audit trail of security-relevant and administrative actions. */
public class AuditService {

    private static final Logger LOG = Logger.getLogger(AuditService.class.getName());

    private final AuditDao dao;

    public AuditService(AuditDao dao) { this.dao = dao; }

    public void log(String actor, String action, String details, String ip) {
        try {
            dao.save(new AuditEntry(actor == null ? "public" : actor, action,
                    details == null ? "" : details.length() > 400 ? details.substring(0, 400) : details, ip));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not write audit entry " + action, e);   // never break the real action
        }
    }

    public List<AuditEntry> recent(int limit) { return dao.findRecent(Math.max(1, Math.min(limit, 500))); }
}
