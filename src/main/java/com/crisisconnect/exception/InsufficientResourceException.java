package com.crisisconnect.exception;

/** Thrown when more units are requested than are available. */
public class InsufficientResourceException extends CrisisConnectException {
    private static final long serialVersionUID = 1L;

    private final int requested;
    private final int available;

    public InsufficientResourceException(String resourceName, int requested, int available) {
        super(String.format("Cannot allocate %d units of '%s' - only %d available",
                requested, resourceName, available));
        this.requested = requested;
        this.available = available;
    }

    public int getRequested() { return requested; }
    public int getAvailable() { return available; }
}
