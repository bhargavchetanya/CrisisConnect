package com.crisisconnect.model;

/** Shelter beds. Victims must travel to the shelter, so distance is penalised heavily. */
public class ShelterResource extends Resource {
    private static final long serialVersionUID = 1L;

    public ShelterResource() { }
    public ShelterResource(int orgId, String name, int qty, GeoLocation loc) { super(orgId, name, qty, loc); }

    @Override public ResourceType getType() { return ResourceType.SHELTER; }

    @Override
    protected double typeAdjustedCost(double distanceKm) {
        return distanceKm * 1.5;
    }
}
