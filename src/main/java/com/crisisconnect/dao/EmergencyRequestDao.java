package com.crisisconnect.dao;

import com.crisisconnect.model.EmergencyRequest;
import com.crisisconnect.model.RequestStatus;
import com.crisisconnect.model.UrgencyLevel;

import java.util.List;
import java.util.Optional;

public interface EmergencyRequestDao extends GenericDao<EmergencyRequest, Integer> {

    /** OPEN + PARTIALLY_FULFILLED requests. */
    List<EmergencyRequest> findActive();

    List<EmergencyRequest> findByStatus(RequestStatus status);

    boolean updateProgress(int requestId, int quantityFulfilled, RequestStatus status);

    /** Public SOS tracking (CC-XXXXXX). */
    Optional<EmergencyRequest> findByTrackingCode(String code);

    boolean updateUrgency(int requestId, UrgencyLevel urgency);

    /** Records an officer's decision on an SOS. */
    boolean updateVerification(int requestId, com.crisisconnect.model.Verification verification, String by,
                               java.time.LocalDateTime at, String note);
}
