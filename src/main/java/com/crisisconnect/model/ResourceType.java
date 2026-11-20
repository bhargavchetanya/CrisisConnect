package com.crisisconnect.model;

/** Categories of resources an organization can register. */
public enum ResourceType {
    FOOD("Food & Water", "packs"),
    MEDICAL("Medical Supplies", "units"),
    SHELTER("Shelter Beds", "beds"),
    VEHICLE("Rescue Vehicles", "vehicles"),
    VOLUNTEER("Volunteers", "volunteers");

    private final String label;
    private final String unit;

    ResourceType(String label, String unit) {
        this.label = label;
        this.unit = unit;
    }

    public String getLabel() { return label; }
    public String getUnit() { return unit; }

    @Override
    public String toString() { return label; }
}
