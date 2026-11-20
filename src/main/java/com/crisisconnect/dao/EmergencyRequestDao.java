package com.crisisconnect.dao;

import com.crisisconnect.model.EmergencyRequest;
import com.crisisconnect.model.RequestStatus;

import java.util.List;

public interface EmergencyRequestDao extends GenericDao<EmergencyRequest, Integer> {

    /** OPEN + PARTIALLY_FULFILLED requests. */
    List<EmergencyRequest> findActive();

    List<EmergencyRequest> findByStatus(RequestStatus status);

    boolean updateProgress(int requestId, int quantityFulfilled, RequestStatus status);
}
