package com.crisisconnect.dao;

import com.crisisconnect.model.Allocation;

import java.util.List;

public interface AllocationDao extends GenericDao<Allocation, Integer> {

    List<Allocation> findByRequest(int requestId);

    /** Most recent allocations first, limited. */
    List<Allocation> findRecent(int limit);
}
