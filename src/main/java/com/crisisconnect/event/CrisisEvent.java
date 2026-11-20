package com.crisisconnect.event;

import java.time.LocalDateTime;

/** Immutable event published whenever something important happens (record = Java 16+). */
public record CrisisEvent(Type type, String message, LocalDateTime time) {

    public enum Type { REQUEST_CREATED, REQUEST_UPDATED, RESOURCE_ADDED, RESOURCE_REMOVED,
        ORGANIZATION_ADDED, MATCH_COMPLETED, DATA_IMPORTED, DATA_RESTORED }

    public CrisisEvent(Type type, String message) {
        this(type, message, LocalDateTime.now());
    }
}
