package com.crisisconnect.model;

/** Medicines, blood units, oxygen cylinders ... Perishable and time-critical. */
public class MedicalResource extends Resource {
    private static final long serialVersionUID = 1L;

    public MedicalResource() { }
    public MedicalResource(int orgId, String name, int qty, GeoLocation loc) { super(orgId, name, qty, loc); }

    @Override public ResourceType getType() { return ResourceType.MEDICAL; }

    @Override
    protected double typeAdjustedCost(double distanceKm) {
        long days = daysToExpiry();
        double cost = distanceKm * 1.2;                // medical supplies: distance matters more
        if (days <= 7) cost -= 15;                     // use near-expiry stock first
        return Math.max(0, cost);
    }
}
