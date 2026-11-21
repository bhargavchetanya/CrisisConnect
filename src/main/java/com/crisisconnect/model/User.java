package com.crisisconnect.model;

import java.time.LocalDateTime;

/** A coordinator or administrator account for the web console. */
public class User extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String email;
    private String name;
    private transient String passwordHash;     // never serialised into backups
    private Role role = Role.COORDINATOR;
    private UserStatus status = UserStatus.PENDING;
    private Integer organizationId;            // optional: coordinator of one organization
    private String organizationName;           // filled by a JOIN
    private boolean emailVerified;
    private int failedLogins;
    private LocalDateTime lockUntil;
    private LocalDateTime lastLoginAt;

    public User() { }

    public User(String email, String name, String passwordHash) {
        this.email = email;
        this.name = name;
        this.passwordHash = passwordHash;
    }

    public boolean isLocked() {
        return lockUntil != null && lockUntil.isAfter(LocalDateTime.now());
    }

    public boolean isAdmin() { return role == Role.ADMIN; }

    @Override
    public String displayName() { return name + " <" + email + "> (" + role + ", " + status + ")"; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public UserStatus getStatus() { return status; }
    public void setStatus(UserStatus status) { this.status = status; }
    public Integer getOrganizationId() { return organizationId; }
    public void setOrganizationId(Integer organizationId) { this.organizationId = organizationId; }
    public String getOrganizationName() { return organizationName; }
    public void setOrganizationName(String organizationName) { this.organizationName = organizationName; }
    public boolean isEmailVerified() { return emailVerified; }
    public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }
    public int getFailedLogins() { return failedLogins; }
    public void setFailedLogins(int failedLogins) { this.failedLogins = failedLogins; }
    public LocalDateTime getLockUntil() { return lockUntil; }
    public void setLockUntil(LocalDateTime lockUntil) { this.lockUntil = lockUntil; }
    public LocalDateTime getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(LocalDateTime lastLoginAt) { this.lastLoginAt = lastLoginAt; }
}
