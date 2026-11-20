package com.crisisconnect.model;

/** Kind of emergency that triggered a request. */
public enum DisasterType {
    FLOOD("Flood"),
    ACCIDENT("Road / Industrial Accident"),
    MEDICAL_SHORTAGE("Medical Shortage"),
    FIRE("Fire"),
    EARTHQUAKE("Earthquake"),
    HEATWAVE("Heatwave"),
    OTHER("Other");

    private final String label;

    DisasterType(String label) { this.label = label; }

    public String getLabel() { return label; }

    @Override
    public String toString() { return label; }
}
