package com.crisisconnect.model;

import com.crisisconnect.exception.InsufficientResourceException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * Abstract resource registered by an organization.
 * Each concrete subclass (Food, Medical, Shelter, Vehicle, Volunteer) overrides
 * {@link #typeAdjustedCost(double)} so the matching engine can rank resources
 * polymorphically - runtime polymorphism in action.
 */
public abstract class Resource extends BaseEntity implements Allocatable {

    private static final long serialVersionUID = 1L;

    private int organizationId;
    private String organizationName;
    private String name;
    private int availableQuantity;
    private int totalCapacity;
    private GeoLocation location;
    private LocalDate expiryDate;          // nullable -> exposed as Optional

    protected Resource() { }

    protected Resource(int organizationId, String name, int quantity, GeoLocation location) {
        this.organizationId = organizationId;
        this.name = Objects.requireNonNull(name);
        this.availableQuantity = quantity;
        this.totalCapacity = quantity;
        this.location = Objects.requireNonNull(location);
    }

    /** Which category this resource belongs to. */
    public abstract ResourceType getType();

    /**
     * Converts the raw distance into a "matching cost" (lower = better).
     * Subclasses tweak this based on the nature of the resource.
     */
    protected abstract double typeAdjustedCost(double distanceKm);

    /** Final cost used by the matching engine for a given target location. */
    public final double matchCost(GeoLocation target) {
        return typeAdjustedCost(location.distanceTo(target));
    }

    // ---------- Allocatable ----------
    @Override
    public void allocate(int quantity) throws InsufficientResourceException {
        if (!canAllocate(quantity)) {
            throw new InsufficientResourceException(name, quantity, availableQuantity);
        }
        availableQuantity -= quantity;
    }

    @Override
    public void release(int quantity) {
        availableQuantity = Math.min(totalCapacity, availableQuantity + Math.max(0, quantity));
    }

    /** 0.0 = untouched, 1.0 = fully used. */
    public double utilization() {
        return totalCapacity == 0 ? 0 : 1.0 - ((double) availableQuantity / totalCapacity);
    }

    public Optional<LocalDate> getExpiryDate() { return Optional.ofNullable(expiryDate); }

    /** Days until expiry; Long.MAX_VALUE if it never expires. */
    public long daysToExpiry() {
        return getExpiryDate()
                .map(d -> ChronoUnit.DAYS.between(LocalDate.now(), d))
                .orElse(Long.MAX_VALUE);
    }

    @Override
    public String displayName() {
        return String.format("%s - %d %s @ %s", name, availableQuantity, getType().getUnit(), getCity());
    }

    // ---------- getters / setters ----------
    public int getOrganizationId() { return organizationId; }
    public void setOrganizationId(int organizationId) { this.organizationId = organizationId; }
    public String getOrganizationName() { return organizationName; }
    public void setOrganizationName(String organizationName) { this.organizationName = organizationName; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    @Override
    public int getAvailableQuantity() { return availableQuantity; }
    public void setAvailableQuantity(int availableQuantity) { this.availableQuantity = availableQuantity; }
    public int getTotalCapacity() { return totalCapacity; }
    public void setTotalCapacity(int totalCapacity) { this.totalCapacity = totalCapacity; }
    public GeoLocation getLocation() { return location; }
    public void setLocation(GeoLocation location) { this.location = location; }
    public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }
    public String getCity() { return location == null ? "" : location.getCity(); }
    public String getUnit() { return getType().getUnit(); }
}
