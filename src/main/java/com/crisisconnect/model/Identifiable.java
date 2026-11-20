package com.crisisconnect.model;

/**
 * Generic contract for anything that has a unique identifier.
 * Demonstrates: interfaces + generics (Unit 1, Unit 2).
 *
 * @param <ID> type of the identifier (Integer, String, ...)
 */
public interface Identifiable<ID> {
    ID getId();
    void setId(ID id);
}
