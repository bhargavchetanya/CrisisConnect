package com.crisisconnect.model;

/** Account life-cycle: sign-up -> e-mail verified -> approved by an administrator. */
public enum UserStatus {
    /** Signed up (and possibly verified) but not yet approved by an administrator. */
    PENDING,
    ACTIVE,
    /** Switched off by an administrator - cannot log in. */
    DISABLED
}
