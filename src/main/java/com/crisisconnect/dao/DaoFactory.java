package com.crisisconnect.dao;

import com.crisisconnect.dao.jdbc.JdbcAllocationDao;
import com.crisisconnect.dao.jdbc.JdbcEmergencyRequestDao;
import com.crisisconnect.dao.jdbc.JdbcOrganizationDao;
import com.crisisconnect.dao.jdbc.JdbcResourceDao;

/** Hands out DAO implementations - the only class that knows they are JDBC based. */
public final class DaoFactory {

    private static final OrganizationDao ORGS = new JdbcOrganizationDao();
    private static final ResourceDao RESOURCES = new JdbcResourceDao();
    private static final EmergencyRequestDao REQUESTS = new JdbcEmergencyRequestDao();
    private static final AllocationDao ALLOCATIONS = new JdbcAllocationDao();

    private DaoFactory() { }

    public static OrganizationDao organizations() { return ORGS; }
    public static ResourceDao resources() { return RESOURCES; }
    public static EmergencyRequestDao requests() { return REQUESTS; }
    public static AllocationDao allocations() { return ALLOCATIONS; }
}
