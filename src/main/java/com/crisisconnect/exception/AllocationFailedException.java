package com.crisisconnect.exception;

/** Thrown when a transaction that dispatches resources had to be rolled back. */
public class AllocationFailedException extends CrisisConnectException {
    private static final long serialVersionUID = 1L;

    public AllocationFailedException(String message, Throwable cause) { super(message, cause); }
}
