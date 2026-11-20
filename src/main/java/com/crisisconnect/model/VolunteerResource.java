package com.crisisconnect.model;

/** Trained volunteers (NSS / NCC / civil defence). */
public class VolunteerResource extends Resource {
    private static final long serialVersionUID = 1L;

    public VolunteerResource() { }
    public VolunteerResource(int orgId, String name, int qty, GeoLocation loc) { super(orgId, name, qty, loc); }

    @Override public ResourceType getType() { return ResourceType.VOLUNTEER; }

    @Override
    protected double typeAdjustedCost(double distanceKm) {
        return distanceKm;
    }
}
