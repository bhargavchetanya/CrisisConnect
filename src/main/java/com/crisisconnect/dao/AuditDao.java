package com.crisisconnect.dao;

import com.crisisconnect.model.AuditEntry;

import java.util.List;

public interface AuditDao {

    void save(AuditEntry e);

    List<AuditEntry> findRecent(int limit);
}
