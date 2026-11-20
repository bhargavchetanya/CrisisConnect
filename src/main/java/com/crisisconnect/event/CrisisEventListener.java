package com.crisisconnect.event;

/** Observer pattern: anything (JavaFX UI, logger, API) can listen for events. */
@FunctionalInterface
public interface CrisisEventListener {
    void onEvent(CrisisEvent event);
}
