package com.crisisconnect.model;

/** Who did what, and when - shown to administrators in the web console. */
public class AuditEntry extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String actor;     // e-mail of the user, or "public" / "system"
    private String action;    // LOGIN, RUN_MATCH, RESOURCE_ADDED ...
    private String details;
    private String ip;

    public AuditEntry() { }

    public AuditEntry(String actor, String action, String details, String ip) {
        this.actor = actor;
        this.action = action;
        this.details = details;
        this.ip = ip;
    }

    @Override
    public String displayName() { return actor + " " + action + " " + details; }

    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
}
