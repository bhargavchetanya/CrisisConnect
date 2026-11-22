package com.crisisconnect.model;

/**
 * Who may do what on the web portal.
 * The public (no account) can view live operations, raise an SOS and track it.
 */
public enum Role {
    /** Staff of one organization: offers and restocks that organization's resources; dispatch is decided by the team. */
    ORGANIZATION("Organization"),
    /** Runs Smart Match and manages requests, resources and organizations. */
    COORDINATOR("Coordinator"),
    /** Everything a coordinator can do, plus user management and the audit log. */
    ADMIN("Administrator");

    private final String label;

    Role(String label) { this.label = label; }

    public String getLabel() { return label; }

    public boolean atLeast(Role other) { return this.ordinal() >= other.ordinal(); }
}
