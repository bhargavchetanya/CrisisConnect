package com.crisisconnect.dao;

import com.crisisconnect.model.Resource;
import com.crisisconnect.model.ResourceType;

import java.util.List;

public interface ResourceDao extends GenericDao<Resource, Integer> {

    List<Resource> findByType(ResourceType type);

    /** Only resources with available_quantity > 0. */
    List<Resource> findAvailable();

    boolean updateQuantity(int resourceId, int newAvailableQuantity);
}
