package com.crisisconnect.model;

/** Life-cycle of an emergency request. */
public enum RequestStatus {
    OPEN, PARTIALLY_FULFILLED, FULFILLED, CANCELLED;

    /** A request is "active" while it can still receive resources. */
    public boolean isActive() {
        return this == OPEN || this == PARTIALLY_FULFILLED;
    }
}
