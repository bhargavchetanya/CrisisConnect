package com.crisisconnect.dao;

import com.crisisconnect.model.Organization;

import java.util.Optional;

public interface OrganizationDao extends GenericDao<Organization, Integer> {
    Optional<Organization> findByName(String name);
}
