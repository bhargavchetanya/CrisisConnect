package com.crisisconnect.exception;

/**
 * Root of the application's CHECKED exception hierarchy.
 *
 * <pre>
 * Throwable
 *  └─ Exception
 *      ├─ CrisisConnectException (checked)
 *      │    ├─ ValidationException
 *      │    ├─ InsufficientResourceException
 *      │    ├─ EntityNotFoundException
 *      │    └─ AllocationFailedException
 *      └─ RuntimeException
 *           └─ DataAccessException (unchecked wrapper for SQLException)
 * </pre>
 */
public class CrisisConnectException extends Exception {
    private static final long serialVersionUID = 1L;

    public CrisisConnectException(String message) { super(message); }
    public CrisisConnectException(String message, Throwable cause) { super(message, cause); }
}
