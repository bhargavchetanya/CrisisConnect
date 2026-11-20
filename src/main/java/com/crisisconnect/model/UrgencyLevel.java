package com.crisisconnect.model;

/**
 * Urgency of an emergency request. The weight feeds the priority score
 * used by the PriorityQueue in the matching engine.
 */
public enum UrgencyLevel {
    CRITICAL(100, "#ff3b3b"),
    HIGH(70, "#ff8c1a"),
    MEDIUM(40, "#f5c518"),
    LOW(15, "#3ddc97");

    private final int weight;
    private final String colorHex;

    UrgencyLevel(int weight, String colorHex) {
        this.weight = weight;
        this.colorHex = colorHex;
    }

    public int getWeight() { return weight; }
    public String getColorHex() { return colorHex; }
}
