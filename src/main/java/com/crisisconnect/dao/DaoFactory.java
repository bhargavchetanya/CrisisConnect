package com.crisisconnect.dao;

import com.crisisconnect.dao.jdbc.JdbcAllocationDao;
import com.crisisconnect.dao.jdbc.JdbcAuditDao;
import com.crisisconnect.dao.jdbc.JdbcEmergencyRequestDao;
import com.crisisconnect.dao.jdbc.JdbcNotificationDao;
import com.crisisconnect.dao.jdbc.JdbcOrganizationDao;
import com.crisisconnect.dao.jdbc.JdbcResourceDao;
import com.crisisconnect.dao.jdbc.JdbcUserDao;

/** Hands out DAO implementations - the only class that knows they are JDBC based. */
public final class DaoFactory {

    private static final OrganizationDao ORGS = new JdbcOrganizationDao();
    private static final ResourceDao RESOURCES = new JdbcResourceDao();
    private static final EmergencyRequestDao REQUESTS = new JdbcEmergencyRequestDao();
    private static final AllocationDao ALLOCATIONS = new JdbcAllocationDao();
    private static final UserDao USERS = new JdbcUserDao();
    private static final NotificationDao NOTIFICATIONS = new JdbcNotificationDao();
    private static final AuditDao AUDIT = new JdbcAuditDao();

    private DaoFactory() { }

    public static OrganizationDao organizations() { return ORGS; }
    public static ResourceDao resources() { return RESOURCES; }
    public static EmergencyRequestDao requests() { return REQUESTS; }
    public static AllocationDao allocations() { return ALLOCATIONS; }
    public static UserDao users() { return USERS; }
    public static NotificationDao notifications() { return NOTIFICATIONS; }
    public static AuditDao audit() { return AUDIT; }
}
