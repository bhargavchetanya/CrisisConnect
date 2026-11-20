package com.crisisconnect.model;

/**
 * Factory pattern: creates the correct Resource subclass for a ResourceType.
 * Uses a Java 17 switch expression.
 */
public final class ResourceFactory {

    private ResourceFactory() { } // utility class - no objects

    public static Resource create(ResourceType type) {
        return switch (type) {
            case FOOD -> new FoodResource();
            case MEDICAL -> new MedicalResource();
            case SHELTER -> new ShelterResource();
            case VEHICLE -> new VehicleResource();
            case VOLUNTEER -> new VolunteerResource();
        };
    }

    public static Resource create(ResourceType type, int orgId, String name, int qty, GeoLocation loc) {
        Resource r = create(type);
        r.setOrganizationId(orgId);
        r.setName(name);
        r.setAvailableQuantity(qty);
        r.setTotalCapacity(qty);
        r.setLocation(loc);
        return r;
    }
}
