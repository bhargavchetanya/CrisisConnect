package com.crisisconnect.service;

import com.crisisconnect.dao.OrganizationDao;
import com.crisisconnect.dao.UserDao;
import com.crisisconnect.exception.AuthException;
import com.crisisconnect.exception.EntityNotFoundException;
import com.crisisconnect.exception.ValidationException;
import com.crisisconnect.model.Organization;
import com.crisisconnect.model.Role;
import com.crisisconnect.model.User;
import com.crisisconnect.model.UserStatus;
import com.crisisconnect.util.Security;
import com.crisisconnect.util.Validator;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Accounts, login and roles for the web console.
 * <p>
 * The rules follow the authentication design of our Partner in Project platform, re-implemented in plain Java:
 * <ul>
 *   <li>sign-up with a 6-digit e-mail code (10 min, 5 tries), then an administrator approves the account</li>
 *   <li>passwords hashed with PBKDF2 (see {@link Security}); at least 8 characters with a letter and a digit</li>
 *   <li>5 wrong passwords lock the account for 15 minutes; the same error text for unknown e-mail and wrong
 *       password (no account enumeration)</li>
 *   <li>random 24-hour session tokens in an httpOnly cookie; only their SHA-256 is stored</li>
 *   <li>administrators come from the {@code ADMIN_EMAILS} setting and are created at start-up</li>
 * </ul>
 */
public class AuthService {

    private static final Logger LOG = Logger.getLogger(AuthService.class.getName());

    public static final int MAX_FAILED_LOGINS = 5;
    public static final int LOCK_MINUTES = 15;
    public static final int CODE_MINUTES = 10;
    public static final int MAX_CODE_ATTEMPTS = 5;
    public static final String DEMO_EMAIL = "coordinator@demo.crisisconnect.in";
    public static final String DEMO_PASSWORD = "DemoPass#2026";

    /** Result of a successful login: the raw session token goes into the cookie, never into the database. */
    public record Login(User user, String token, LocalDateTime expiresAt) { }

    /** Result of a sign-up. {@code devCode} is only filled when no e-mail provider is configured (demo mode). */
    public record Registration(User user, String devCode) { }

    private final UserDao users;
    private final OrganizationDao orgs;
    private final NotificationService notifications;
    private final AuditService audit;
    private final List<String> adminEmails;
    private final int sessionHours;
    private final boolean showDevCodes;
    private final String dummyHash = Security.hashPassword("timing-equaliser-not-a-real-password");

    public AuthService(UserDao users, OrganizationDao orgs, NotificationService notifications, AuditService audit,
                       List<String> adminEmails, int sessionHours, boolean showDevCodes) {
        this.users = Objects.requireNonNull(users);
        this.orgs = Objects.requireNonNull(orgs);
        this.notifications = Objects.requireNonNull(notifications);
        this.audit = Objects.requireNonNull(audit);
        this.adminEmails = adminEmails.stream().map(AuthService::normEmail).filter(e -> !e.isEmpty()).toList();
        this.sessionHours = sessionHours;
        this.showDevCodes = showDevCodes;
    }

    public static String normEmail(String e) { return e == null ? "" : e.trim().toLowerCase(Locale.ROOT); }

    public boolean isAdminEmail(String email) { return adminEmails.contains(normEmail(email)); }

    public List<String> adminEmails() { return adminEmails; }

    // =====================================================================
    //  Start-up: administrator + demo account
    // =====================================================================
    /**
     * Makes sure every address in ADMIN_EMAILS has an active administrator account.
     * Password = ADMIN_PASSWORD if configured, otherwise a random one printed once to the server log.
     */
    public void bootstrapAdmins(String configuredPassword) {
        boolean hasPassword = configuredPassword != null && !configuredPassword.isBlank();
        for (String email : adminEmails) {
            Optional<User> existing = users.findByEmail(email);
            if (existing.isPresent()) {
                User u = existing.get();
                boolean changed = u.getRole() != Role.ADMIN || u.getStatus() != UserStatus.ACTIVE || !u.isEmailVerified();
                u.setRole(Role.ADMIN);
                u.setStatus(UserStatus.ACTIVE);
                u.setEmailVerified(true);
                if (changed) users.update(u);
                if (hasPassword && !Security.verifyPassword(configuredPassword, u.getPasswordHash())) {
                    users.updatePassword(u.getId(), Security.hashPassword(configuredPassword));
                    LOG.info("Administrator password for " + email + " updated from ADMIN_PASSWORD");
                }
                continue;
            }
            String password = hasPassword ? configuredPassword : Security.randomPassword();
            User u = new User(email, "Administrator", Security.hashPassword(password));
            u.setRole(Role.ADMIN);
            u.setStatus(UserStatus.ACTIVE);
            u.setEmailVerified(true);
            users.save(u);
            audit.log("system", "ADMIN_CREATED", email, null);
            if (hasPassword) {
                LOG.info("Administrator account ready: " + email + " (password from ADMIN_PASSWORD)");
            } else {
                LOG.warning("""

                        ==================================================================
                          Administrator account created: %s
                          Temporary password: %s
                          Set the ADMIN_PASSWORD environment variable to choose your own.
                        ==================================================================""".formatted(email, password));
            }
        }
    }

    /** Creates the public demo coordinator (only for the demo scenario). */
    public void seedDemoCoordinator() {
        if (users.findByEmail(DEMO_EMAIL).isPresent()) return;
        User u = new User(DEMO_EMAIL, "Demo Coordinator", Security.hashPassword(DEMO_PASSWORD));
        u.setRole(Role.COORDINATOR);
        u.setStatus(UserStatus.ACTIVE);
        u.setEmailVerified(true);
        orgs.findByName("NDRF 7th Battalion").ifPresent(o -> u.setOrganizationId(o.getId()));
        users.save(u);
        LOG.info("Demo coordinator ready: " + DEMO_EMAIL + " / " + DEMO_PASSWORD);
    }

    // =====================================================================
    //  Sign-up and e-mail verification
    // =====================================================================
    public Registration register(String name, String email, String password, Integer organizationId, String ip)
            throws ValidationException {
        String mail = normEmail(email);
        Validator.start()
                .notBlank(name, "Name")
                .notBlank(mail, "E-mail")
                .email(mail, "E-mail")
                .validate();
        checkPasswordStrength(password);
        if (name.trim().length() > 80) throw new ValidationException("Name must be at most 80 characters");
        if (organizationId != null && orgs.findById(organizationId).isEmpty()) {
            throw new ValidationException("Please choose a valid organization");
        }
        if (isAdminEmail(mail)) {
            throw new ValidationException("This e-mail already has an administrator account. Log in instead.");
        }
        Optional<User> existing = users.findByEmail(mail);
        User u;
        if (existing.isPresent()) {
            u = existing.get();
            if (u.isEmailVerified()) {
                throw new ValidationException("This e-mail is already registered. Log in instead, or use a different e-mail.");
            }
            // an abandoned, never-verified sign-up: let the person start again (they still need the inbox)
            u.setName(name.trim());
            u.setOrganizationId(organizationId);
            u.setRole(organizationId != null ? Role.ORGANIZATION : Role.COORDINATOR);
            users.update(u);
            users.updatePassword(u.getId(), Security.hashPassword(password));
            users.deleteSessionsFor(u.getId());
        } else {
            u = new User(mail, name.trim(), Security.hashPassword(password));
            u.setOrganizationId(organizationId);
            u.setRole(organizationId != null ? Role.ORGANIZATION : Role.COORDINATOR);
            users.save(u);
        }
        if (organizationId != null) {
            // Organizations: no code e-mail - the administrator reviews the request, and the first e-mail the
            // organization receives is the approval.
            u.setEmailVerified(true);
            users.update(u);
            audit.log(mail, "SIGN_UP", "requested organization access", ip);
            notifications.newSignupForAdmins(u.getName(), u.getEmail(),
                    orgs.findById(organizationId).map(Organization::getName).orElse(null), "organization request");
            return new Registration(u, null);
        }
        String code = issueCode(u);
        audit.log(mail, "SIGN_UP", "requested coordinator access", ip);
        return new Registration(u, showDevCodes && !notifications.emailEnabled() ? code : null);
    }

    /**
     * A government verification officer asks for access. Like organizations, no code e-mail: the request goes to the
     * administrators (an admin or coordinator approves it once), and the first e-mail the officer gets is the approval.
     */
    public User registerOfficer(String name, String email, String password, String city, String designation, String ip)
            throws ValidationException {
        String mail = normEmail(email);
        Validator.start()
                .notBlank(name, "Name")
                .notBlank(mail, "E-mail")
                .email(mail, "E-mail")
                .notBlank(designation, "Designation / department")
                .validate();
        checkPasswordStrength(password);
        if (name.trim().length() > 80) throw new ValidationException("Name must be at most 80 characters");
        if (designation.trim().length() > 100) throw new ValidationException("Designation must be at most 100 characters");
        String cityName = com.crisisconnect.util.CityDirectory.find(city).map(g -> g.getCity())
                .orElseThrow(() -> new ValidationException("Please choose the city you verify SOS requests for"));
        if (isAdminEmail(mail)) throw new ValidationException("This e-mail already has an administrator account. Log in instead.");
        if (users.findByEmail(mail).isPresent()) {
            throw new ValidationException("This e-mail is already registered. Log in instead, or use a different e-mail.");
        }
        User u = new User(mail, name.trim(), Security.hashPassword(password));
        u.setRole(Role.OFFICER);
        u.setCity(cityName);
        u.setDesignation(designation.trim());
        u.setEmailVerified(true);
        users.save(u);
        audit.log(mail, "SIGN_UP", "requested verification-officer access for " + cityName, ip);
        notifications.newSignupForAdmins(u.getName(), mail,
                "Verification officer - " + u.getDesignation() + ", " + cityName, "officer request");
        return u;
    }

    /** Active verification officers of a city (they get an e-mail for every new SOS there). */
    public List<User> activeOfficersFor(String city) {
        return users.findAll().stream()
                .filter(u -> u.getRole() == Role.OFFICER && u.getStatus() == UserStatus.ACTIVE)
                .filter(u -> u.getCity() != null && u.getCity().equalsIgnoreCase(city == null ? "" : city.trim()))
                .toList();
    }

    private String issueCode(User u) {
        String code = Security.sixDigitCode();
        users.deleteEmailCodes(u.getId());
        users.saveEmailCode(u.getId(), Security.sha256(u.getId() + ":" + code),
                LocalDateTime.now().plusMinutes(CODE_MINUTES));
        notifications.verificationCode(u.getEmail(), u.getName(), code);
        return code;
    }

    /** @return the user after verification (status PENDING until an administrator approves). */
    public User verifyEmail(String email, String code, String ip) throws ValidationException {
        User u = users.findByEmail(normEmail(email)).orElseThrow(() -> new ValidationException("That code is not valid"));
        if (u.isEmailVerified()) return u;
        UserDao.EmailCode c = users.latestEmailCode(u.getId())
                .orElseThrow(() -> new ValidationException("No code found - request a new one"));
        if (c.expiresAt().isBefore(LocalDateTime.now())) throw new ValidationException("This code has expired. Request a new one.");
        if (c.attempts() >= MAX_CODE_ATTEMPTS) throw new ValidationException("Too many wrong codes. Request a new one.");
        String typed = code == null ? "" : code.replaceAll("\\s", "");
        if (!c.codeHash().equals(Security.sha256(u.getId() + ":" + typed))) {
            users.incrementCodeAttempts(c.id());
            throw new ValidationException("That code is not correct");
        }
        users.deleteEmailCodes(u.getId());
        u.setEmailVerified(true);
        users.update(u);
        audit.log(u.getEmail(), "EMAIL_VERIFIED", "awaiting administrator approval", ip);
        notifications.newSignupForAdmins(u.getName(), u.getEmail(),
                u.getOrganizationId() == null ? null : orgs.findById(u.getOrganizationId()).map(Organization::getName).orElse(null),
                "e-mail and password");
        return u;
    }

    /** Sends a fresh code. Same answer whether or not the e-mail exists (no account enumeration). */
    public String resendCode(String email) throws AuthException {
        Optional<User> u = users.findByEmail(normEmail(email));
        if (u.isEmpty() || u.get().isEmailVerified()) return null;
        Optional<UserDao.EmailCode> last = users.latestEmailCode(u.get().getId());
        if (last.isPresent() && Duration.between(last.get().createdAt(), LocalDateTime.now()).getSeconds() < 45) {
            throw new AuthException(429, "TOO_SOON", "Please wait a few seconds before requesting another code.");
        }
        String code = issueCode(u.get());
        return showDevCodes && !notifications.emailEnabled() ? code : null;
    }

    // =====================================================================
    //  Login / logout / sessions
    // =====================================================================
    public Login login(String email, String password, String ip, String userAgent) throws AuthException {
        String mail = normEmail(email);
        Optional<User> found = users.findByEmail(mail);
        AuthException wrong = new AuthException(401, "INVALID_CREDENTIALS", "Incorrect e-mail or password.");
        if (found.isEmpty()) {
            Security.verifyPassword(password == null ? "" : password, dummyHash);    // equalise response time
            audit.log(mail, "LOGIN_FAILED", "unknown e-mail", ip);
            throw wrong;
        }
        User u = found.get();
        if (u.isLocked()) {
            long mins = Math.max(1, Duration.between(LocalDateTime.now(), u.getLockUntil()).toMinutes() + 1);
            throw new AuthException(423, "ACCOUNT_LOCKED",
                    "Too many failed attempts. This account is locked for " + mins + " more minute" + (mins == 1 ? "" : "s") + ".");
        }
        if (!Security.verifyPassword(password, u.getPasswordHash())) {
            u.setFailedLogins(u.getFailedLogins() + 1);
            if (u.getFailedLogins() >= MAX_FAILED_LOGINS) {
                u.setFailedLogins(0);
                u.setLockUntil(LocalDateTime.now().plusMinutes(LOCK_MINUTES));
                users.updateLoginState(u);
                audit.log(mail, "ACCOUNT_LOCKED", MAX_FAILED_LOGINS + " wrong passwords", ip);
                throw new AuthException(423, "ACCOUNT_LOCKED",
                        "Too many failed attempts. This account is locked for " + LOCK_MINUTES + " minutes.");
            }
            users.updateLoginState(u);
            audit.log(mail, "LOGIN_FAILED", "wrong password", ip);
            throw wrong;
        }
        if (!u.isEmailVerified()) {
            throw new AuthException(403, "EMAIL_NOT_VERIFIED", "Please verify your e-mail first - enter the 6-digit code we sent you.");
        }
        if (u.getStatus() == UserStatus.PENDING) {
            throw new AuthException(403, "PENDING_APPROVAL", "Your account is waiting for an administrator to approve it.");
        }
        if (u.getStatus() == UserStatus.DISABLED) {
            throw new AuthException(403, "ACCOUNT_DISABLED", "This account has been disabled by an administrator.");
        }
        u.setFailedLogins(0);
        u.setLockUntil(null);
        u.setLastLoginAt(LocalDateTime.now());
        users.updateLoginState(u);
        String token = Security.randomToken();
        LocalDateTime expires = LocalDateTime.now().plusHours(sessionHours);
        users.createSession(Security.sha256(token), u.getId(), expires,
                userAgent == null ? null : userAgent.substring(0, Math.min(200, userAgent.length())));
        audit.log(mail, "LOGIN", u.getRole().name(), ip);
        return new Login(u, token, expires);
    }

    /**
     * "Continue with Google": Google has already verified the e-mail address.
     * Existing accounts (including the administrator) are logged in; a new person gets a PENDING coordinator
     * account and the administrator is alerted, exactly like an e-mail sign-up.
     */
    public Login loginWithGoogle(GoogleOAuth.Profile p, String ip, String userAgent) throws AuthException {
        if (p == null || p.email() == null || !p.emailVerified()) {
            throw new AuthException(403, "GOOGLE_EMAIL_UNVERIFIED", "Your Google account has no verified e-mail address.");
        }
        String mail = normEmail(p.email());
        User u = users.findByEmail(mail).orElse(null);
        if (u == null) {
            u = new User(mail, p.name() == null || p.name().isBlank() ? mail.substring(0, mail.indexOf('@')) : p.name().trim(),
                    Security.hashPassword(Security.randomToken()));          // random password: Google-only account
            u.setEmailVerified(true);
            if (isAdminEmail(mail)) { u.setRole(Role.ADMIN); u.setStatus(UserStatus.ACTIVE); }
            users.save(u);
            audit.log(mail, "SIGN_UP", "via Google", ip);
            if (u.getStatus() == UserStatus.PENDING) {
                notifications.newSignupForAdmins(u.getName(), mail, null, "Google");
                throw new AuthException(403, "PENDING_APPROVAL",
                        "Thanks " + firstName(u.getName()) + "! Your request was sent to the administrator. You can log in once it is approved.");
            }
        } else if (!u.isEmailVerified()) {
            u.setEmailVerified(true);                                       // Google proved ownership of the address
            users.update(u);
            users.deleteEmailCodes(u.getId());
        }
        if (u.getStatus() == UserStatus.PENDING) {
            throw new AuthException(403, "PENDING_APPROVAL", "Your account is waiting for an administrator to approve it.");
        }
        if (u.getStatus() == UserStatus.DISABLED) {
            throw new AuthException(403, "ACCOUNT_DISABLED", "This account has been disabled by an administrator.");
        }
        u.setFailedLogins(0);
        u.setLockUntil(null);
        u.setLastLoginAt(LocalDateTime.now());
        users.updateLoginState(u);
        String token = Security.randomToken();
        LocalDateTime expires = LocalDateTime.now().plusHours(sessionHours);
        users.createSession(Security.sha256(token), u.getId(), expires,
                userAgent == null ? null : userAgent.substring(0, Math.min(200, userAgent.length())));
        audit.log(mail, "LOGIN", u.getRole().name() + " via Google", ip);
        return new Login(u, token, expires);
    }

    private static String firstName(String name) { return name == null ? "" : name.trim().split("\\s+")[0]; }

    /** The active user behind a session cookie, if the session is valid. */
    public Optional<User> userForToken(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return users.findBySessionHash(Security.sha256(token)).filter(u -> u.getStatus() == UserStatus.ACTIVE);
    }

    public void logout(String token, User user, String ip) {
        if (token != null) users.deleteSession(Security.sha256(token));
        if (user != null) audit.log(user.getEmail(), "LOGOUT", "", ip);
    }

    public void changePassword(User u, String current, String next, String ip) throws ValidationException {
        User fresh = users.findById(u.getId()).orElseThrow();
        if (!Security.verifyPassword(current, fresh.getPasswordHash())) throw new ValidationException("Current password is not correct");
        checkPasswordStrength(next);
        users.updatePassword(u.getId(), Security.hashPassword(next));
        audit.log(u.getEmail(), "PASSWORD_CHANGED", "", ip);
    }

    public int sessionHours() { return sessionHours; }

    public int purgeExpiredSessions() { return users.purgeExpiredSessions(); }

    // =====================================================================
    //  Role checks
    // =====================================================================
    public static void require(User u, Role role) throws AuthException {
        if (u == null) throw AuthException.notLoggedIn();
        if (!u.getRole().atLeast(role)) throw AuthException.forbidden(role.getLabel() + " access only.");
    }

    /** Coordinators linked to an organization may only change that organization's resources. */
    public static void requireOrgScope(User u, int organizationId) throws AuthException {
        if (u.isAdmin()) return;
        if (u.getOrganizationId() == null) {
            if (u.getRole() == Role.ORGANIZATION) {
                throw AuthException.forbidden("Your account is not linked to an organization yet - ask the administrator.");
            }
            return;
        }
        if (u.getOrganizationId() != organizationId) {
            throw AuthException.forbidden("You can only manage resources of " + u.getOrganizationName() + ".");
        }
    }

    // =====================================================================
    //  User administration (ADMIN only - checked by the API layer)
    // =====================================================================
    public List<User> listUsers() { return users.findAll(); }

    public int pendingCount() { return users.findPending().size(); }

    public User approve(User admin, int id, String ip) throws EntityNotFoundException, ValidationException {
        User u = users.findById(id).orElseThrow(() -> new EntityNotFoundException("User", id));
        requireCanManage(admin, u);
        if (!u.isEmailVerified()) throw new ValidationException(u.getEmail() + " has not verified their e-mail yet");
        if (u.getStatus() == UserStatus.ACTIVE) return u;
        u.setStatus(UserStatus.ACTIVE);
        users.update(u);
        audit.log(admin.getEmail(), "USER_APPROVED", u.getEmail() + " as " + u.getRole(), ip);
        notifications.accountApproved(u.getEmail(), u.getName(), u.getRole().getLabel());
        return u;
    }

    public User setStatus(User admin, int id, UserStatus status, String ip) throws EntityNotFoundException, ValidationException {
        User u = protectedTarget(admin, id);
        requireCanManage(admin, u);
        u.setStatus(status);
        users.update(u);
        if (status != UserStatus.ACTIVE) users.deleteSessionsFor(u.getId());     // kick them out immediately
        audit.log(admin.getEmail(), "USER_STATUS", u.getEmail() + " -> " + status, ip);
        return u;
    }

    public User setRole(User admin, int id, Role role, String ip) throws EntityNotFoundException, ValidationException {
        User u = protectedTarget(admin, id);
        u.setRole(role);
        users.update(u);
        audit.log(admin.getEmail(), "USER_ROLE", u.getEmail() + " -> " + role, ip);
        return u;
    }

    public User setOrganization(User admin, int id, Integer orgId, String ip) throws EntityNotFoundException, ValidationException {
        User u = users.findById(id).orElseThrow(() -> new EntityNotFoundException("User", id));
        if (orgId != null && orgs.findById(orgId).isEmpty()) throw new ValidationException("Unknown organization");
        u.setOrganizationId(orgId);
        users.update(u);
        audit.log(admin.getEmail(), "USER_ORGANIZATION", u.getEmail() + " -> " + (orgId == null ? "all organizations" : "org #" + orgId), ip);
        return users.findById(id).orElse(u);
    }

    public void deleteUser(User admin, int id, String ip) throws EntityNotFoundException, ValidationException {
        User u = protectedTarget(admin, id);
        requireCanManage(admin, u);
        users.delete(id);
        audit.log(admin.getEmail(), "USER_DELETED", u.getEmail(), ip);
    }

    /** Users a coordinator may manage: verification officers only. Administrators manage everyone. */
    public List<User> listOfficers() {
        return users.findAll().stream().filter(u -> u.getRole() == Role.OFFICER).toList();
    }

    /** Administrators manage every account; coordinators may approve / disable / remove verification officers only. */
    private static void requireCanManage(User actor, User target) throws ValidationException {
        if (actor.isAdmin()) return;
        if (actor.getRole() == Role.COORDINATOR && target.getRole() == Role.OFFICER) return;
        throw new ValidationException("Only an administrator can manage " + target.getRole().getLabel().toLowerCase() + " accounts");
    }

    /** Administrators cannot lock themselves out, and bootstrap administrators cannot be changed from the web. */
    private User protectedTarget(User admin, int id) throws EntityNotFoundException, ValidationException {
        User u = users.findById(id).orElseThrow(() -> new EntityNotFoundException("User", id));
        if (u.getId().equals(admin.getId())) throw new ValidationException("You cannot change your own account here");
        if (isAdminEmail(u.getEmail())) throw new ValidationException(u.getEmail() + " is a built-in administrator (ADMIN_EMAILS)");
        return u;
    }

    private static void checkPasswordStrength(String p) throws ValidationException {
        if (p == null || p.length() < 8) throw new ValidationException("Password must be at least 8 characters");
        if (p.length() > 128) throw new ValidationException("Password is too long");
        if (!p.matches(".*[A-Za-z].*") || !p.matches(".*[0-9].*")) {
            throw new ValidationException("Password must contain at least one letter and one number");
        }
    }
}
