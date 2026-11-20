package com.crisisconnect.util;

import com.crisisconnect.exception.ValidationException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Fluent validator that collects every error and throws a single ValidationException.
 * Usage: Validator.start().notBlank(name, "Name").positive(qty, "Quantity").validate();
 */
public final class Validator {

    private static final Pattern PHONE = Pattern.compile("^[+]?[0-9 -]{7,15}$");
    private static final Pattern EMAIL = Pattern.compile("^[\\w.+-]+@[\\w-]+\\.[\\w.]+$");

    private final List<String> errors = new ArrayList<>();

    private Validator() { }

    public static Validator start() { return new Validator(); }

    public Validator notBlank(String value, String field) {
        if (value == null || value.isBlank()) errors.add(field + " is required");
        return this;
    }

    public Validator notNull(Object value, String field) {
        if (value == null) errors.add(field + " must be selected");
        return this;
    }

    public Validator positive(int value, String field) {
        if (value <= 0) errors.add(field + " must be greater than 0");
        return this;
    }

    public Validator max(int value, int max, String field) {
        if (value > max) errors.add(field + " must not exceed " + max);
        return this;
    }

    public Validator phone(String value, String field) {
        if (value != null && !value.isBlank() && !PHONE.matcher(value.trim()).matches())
            errors.add(field + " is not a valid phone number");
        return this;
    }

    public Validator email(String value, String field) {
        if (value != null && !value.isBlank() && !EMAIL.matcher(value.trim()).matches())
            errors.add(field + " is not a valid e-mail");
        return this;
    }

    public void validate() throws ValidationException {
        if (!errors.isEmpty()) throw new ValidationException(errors);
    }

    /** Parses an int from a text field, recording an error instead of throwing. */
    public static int parseIntOr(String text, int def) {
        try { return Integer.parseInt(text.trim()); }
        catch (NumberFormatException | NullPointerException e) { return def; }
    }
}
