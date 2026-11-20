package com.crisisconnect.exception;

/**
 * UNCHECKED wrapper around SQLException so that the DAO interfaces stay clean
 * and callers are not forced to handle low-level JDBC errors everywhere.
 */
public class DataAccessException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DataAccessException(String message, Throwable cause) { super(message, cause); }
}
