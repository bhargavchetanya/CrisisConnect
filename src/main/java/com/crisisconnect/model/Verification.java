package com.crisisconnect.model;

/**
 * Is an SOS genuine? Public SOS requests start UNVERIFIED and are checked by a government
 * verification officer of that city (or a coordinator / administrator) before Smart Match may dispatch to them.
 */
public enum Verification {
    UNVERIFIED("Waiting for verification"),
    VERIFIED("Verified"),
    REJECTED("Rejected as not genuine");

    private final String label;

    Verification(String label) { this.label = label; }

    public String getLabel() { return label; }
}
