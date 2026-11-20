package com.crisisconnect.exception;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Thrown when user input breaks a business rule. Carries ALL validation errors at once. */
public class ValidationException extends CrisisConnectException {
    private static final long serialVersionUID = 1L;

    private final ArrayList<String> errors;

    public ValidationException(List<String> errors) {
        super("Validation failed: " + String.join("; ", errors));
        this.errors = new ArrayList<>(errors);
    }

    public ValidationException(String error) {
        this(List.of(error));
    }

    public List<String> getErrors() { return Collections.unmodifiableList(errors); }
}
