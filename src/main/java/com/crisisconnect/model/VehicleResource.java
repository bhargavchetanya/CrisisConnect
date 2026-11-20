package com.crisisconnect.model;

/** Ambulances, boats, trucks. They move, so a long distance is less of a problem. */
public class VehicleResource extends Resource {
    private static final long serialVersionUID = 1L;

    public VehicleResource() { }
    public VehicleResource(int orgId, String name, int qty, GeoLocation loc) { super(orgId, name, qty, loc); }

    @Override public ResourceType getType() { return ResourceType.VEHICLE; }

    @Override
    protected double typeAdjustedCost(double distanceKm) {
        return distanceKm * 0.8;
    }
}
