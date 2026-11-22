package com.crisisconnect.service;

import com.crisisconnect.model.EmergencyRequest;
import com.crisisconnect.model.Organization;

import java.util.List;

/**
 * Observer for typed domain events raised by {@link CrisisService}.
 * (The older {@code EventBus} carries display strings for the activity feed; this interface carries
 * the actual objects so listeners such as the SMS/WhatsApp notifier can build precise messages.)
 * Every method has an empty default so a listener only overrides what it needs.
 */
public interface CrisisNotifier {

    default void requestCreated(EmergencyRequest request) { }

    /** Called after a request's dispatches were committed (request status is already updated). */
    default void resourcesDispatched(EmergencyRequest request, List<AllocationPlan> dispatches) { }

    default void requestCancelled(EmergencyRequest request) { }

    /** An officer / coordinator confirmed that an SOS is genuine - it can now receive resources. */
    default void requestVerified(EmergencyRequest request) { }

    /** An officer / coordinator rejected an SOS as not genuine (it is closed). */
    default void requestRejected(EmergencyRequest request) { }

    /** Called once after every Smart Match run. */
    default void matchCompleted(MatchReport report) { }

    default void organizationRegistered(Organization organization) { }
}
