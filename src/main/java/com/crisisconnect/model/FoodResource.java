package com.crisisconnect.model;

/** Food packets / drinking water. Perishable: items close to expiry are used first. */
public class FoodResource extends Resource {
    private static final long serialVersionUID = 1L;

    public FoodResource() { }
    public FoodResource(int orgId, String name, int qty, GeoLocation loc) { super(orgId, name, qty, loc); }

    @Override public ResourceType getType() { return ResourceType.FOOD; }

    @Override
    protected double typeAdjustedCost(double distanceKm) {
        long days = daysToExpiry();
        // Expiring within 3 days -> pretend it is 25 km closer so it gets used before it is wasted
        return days <= 3 ? Math.max(0, distanceKm - 25) : distanceKm;
    }
}
