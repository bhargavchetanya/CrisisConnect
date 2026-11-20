package com.crisisconnect.model;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Abstract parent of every persisted entity in CrisisConnect.
 * Demonstrates: abstract classes, encapsulation, Serializable (Unit 3),
 * equals/hashCode using java.util.Objects (Unit 2 utility classes).
 */
public abstract class BaseEntity implements Identifiable<Integer>, Serializable {

    private static final long serialVersionUID = 1L;

    protected Integer id;
    protected LocalDateTime createdAt;

    protected BaseEntity() {
        this.createdAt = LocalDateTime.now();
    }

    /** Every entity must be able to describe itself in one line (used by UI lists and logs). */
    public abstract String displayName();

    @Override
    public Integer getId() { return id; }

    @Override
    public void setId(Integer id) { this.id = id; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = Objects.requireNonNullElse(createdAt, LocalDateTime.now());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BaseEntity that = (BaseEntity) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getClass().getSimpleName(), id);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "#" + id + " [" + displayName() + "]";
    }
}
