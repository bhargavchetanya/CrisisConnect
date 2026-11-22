package com.crisisconnect.api;

import com.crisisconnect.util.CityDirectory;
import com.crisisconnect.service.CrisisService;
import com.crisisconnect.model.OrganizationType;
import com.crisisconnect.model.Organization;
import com.crisisconnect.exception.AuthException;
import com.crisisconnect.model.Role;
import com.crisisconnect.model.User;
import com.crisisconnect.service.AuditService;
import com.crisisconnect.service.AuthService;
import com.crisisconnect.service.GoogleOAuth;
import com.crisisconnect.util.Security;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** /api/auth/* - sign-up with e-mail code, login / logout, current user, change password. */
final class AuthApi {

    private final AuthService auth;
    private final AuditService audit;
    private final GoogleOAuth google;
    private final String publicUrl;
    private final RateLimiter limiter = new RateLimiter(30, 15 * 60_000L);   // 30 tries / 15 min / IP / action

    /** Pending Google sign-ins: state -> PKCE verifier + where to go afterwards (10 minutes). */
    private record PendingGoogle(String verifier, String next, long expiresAt) { }
    private final Map<String, PendingGoogle> pending = new ConcurrentHashMap<>();

    private final CrisisService crisis;

    AuthApi(AuthService auth, AuditService audit, GoogleOAuth google, String publicUrl, CrisisService crisis) {
        this.crisis = crisis;
        this.auth = auth;
        this.audit = audit;
        this.google = google;
        this.publicUrl = publicUrl;
    }

    private String redirectUri() { return publicUrl + "/api/auth/google/callback"; }

    private static String safeNext(String next) {
        return next != null && next.startsWith("/") && !next.startsWith("//") && !next.contains("\\") ? next : "/admin.html";
    }

    private static Redirect toLogin(String key, String value) {
        return new Redirect("/login.html?" + key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    void register(Router api) {
        api.secure("GET", "/api/auth/providers", c -> Map.of("google", google != null && google.isConfigured()))
           .secure("GET", "/api/auth/google", c -> {
               if (google == null || !google.isConfigured()) return toLogin("error", "Google sign-in is not configured yet.");
               if (!limiter.allow("google:" + c.ip())) return toLogin("error", "Too many attempts. Please wait a few minutes.");
               long now = System.currentTimeMillis();
               pending.values().removeIf(p -> p.expiresAt() < now);
               String state = Security.randomToken();
               String verifier = GoogleOAuth.newVerifier();
               pending.put(state, new PendingGoogle(verifier, safeNext(c.query("next")), now + 10 * 60_000L));
               return new Redirect(google.authorizationUrl(redirectUri(), state, verifier));
           })
           .secure("GET", "/api/auth/google/callback", c -> {
               if (c.query("error") != null) return toLogin("error", "Google sign-in was cancelled.");
               PendingGoogle p = c.query("state") == null ? null : pending.remove(c.query("state"));
               if (p == null || p.expiresAt() < System.currentTimeMillis() || c.query("code") == null) {
                   return toLogin("error", "Google sign-in expired - please try again.");
               }
               try {
                   GoogleOAuth.Profile profile = google.exchange(c.query("code"), p.verifier(), redirectUri());
                   var login = auth.loginWithGoogle(profile, c.ip(), c.userAgent());
                   c.setSession(login.token(), Duration.between(LocalDateTime.now(), login.expiresAt()).getSeconds());
                   return new Redirect(p.next());
               } catch (AuthException e) {
                   return toLogin("PENDING_APPROVAL".equals(e.getCode()) ? "pending" : "error", e.getMessage());
               } catch (Exception e) {
                   java.util.logging.Logger.getLogger(AuthApi.class.getName()).warning("Google sign-in failed: " + e);
                   return toLogin("error", "Google sign-in failed. Please try again.");
               }
           });
        api.secure("GET", "/api/auth/me", c -> {
               Map<String, Object> out = new LinkedHashMap<>();          // Map.of() does not allow null values
               out.put("user", c.user().map(AuthApi::session).orElse(null));
               return out;
           })
           .secure("POST", "/api/auth/register", c -> {
               limit(c, "register");
               if ("officer".equals(c.field("accountType"))) {   // government verification officer
                   User off = auth.registerOfficer(c.field("name"), c.field("email"), c.form().get("password"),
                           c.field("officerCity"), c.field("designation"), c.ip());
                   Map<String, Object> out = new LinkedHashMap<>();
                   out.put("ok", true);
                   out.put("email", off.getEmail());
                   out.put("needsCode", false);
                   out.put("message", "Request sent. An administrator or coordinator will confirm you as the verification officer for "
                           + off.getCity() + " - you will get an e-mail at " + off.getEmail() + " once it is approved.");
                   c.status(201);
                   return out;
               }
               Integer orgId = c.optionalInt("organizationId");
               Organization created = null;
               if (!c.field("newOrgName").isEmpty()) {          // "my organization is not listed"
                   Organization o = new Organization();
                   o.setName(c.field("newOrgName"));
                   o.setType(c.field("newOrgType").isEmpty() ? null : OrganizationType.valueOf(c.field("newOrgType")));
                   o.setLocation(CityDirectory.find(c.field("newOrgCity")).orElse(null));
                   o.setContactPhone(c.field("newOrgPhone"));
                   o.setEmail(c.field("email"));
                   created = crisis.registerOrganization(o, false);   // the sign-up e-mail tells the administrator
                   orgId = created.getId();
               }
               AuthService.Registration reg;
               try {
                   reg = auth.register(c.field("name"), c.field("email"), c.form().get("password"), orgId, c.ip());
               } catch (Exception e) {
                   if (created != null) crisis.deleteOrganization(created.getId());   // keep the list clean
                   throw e;
               }
               Map<String, Object> out = new LinkedHashMap<>();
               out.put("ok", true);
               out.put("email", reg.user().getEmail());
               out.put("needsCode", !reg.user().isEmailVerified());
               out.put("message", reg.user().isEmailVerified()
                       ? "Request sent. An administrator will review it - you will get an e-mail at " + reg.user().getEmail() + " once it is approved."
                       : "We sent a 6-digit code to " + reg.user().getEmail() + ". Enter it to verify your e-mail.");
               if (reg.devCode() != null) out.put("devCode", reg.devCode());
               c.status(201);
               return out;
           })
           .secure("POST", "/api/auth/verify", c -> {
               limit(c, "verify");
               User u = auth.verifyEmail(c.field("email"), c.field("code"), c.ip());
               return Map.of("ok", true, "status", u.getStatus().name(),
                       "message", u.getStatus().name().equals("ACTIVE") ? "E-mail verified. You can log in now."
                               : "E-mail verified. An administrator will review your request - you will get an e-mail when it is approved.");
           })
           .secure("POST", "/api/auth/resend", c -> {
               limit(c, "resend");
               String code = auth.resendCode(c.field("email"));
               Map<String, Object> out = new LinkedHashMap<>();
               out.put("ok", true);
               out.put("message", "If that e-mail is waiting for verification, a new code is on its way.");
               if (code != null) out.put("devCode", code);
               return out;
           })
           .secure("POST", "/api/auth/login", c -> {
               limit(c, "login");
               var login = auth.login(c.field("email"), c.form().get("password"), c.ip(), c.userAgent());
               c.setSession(login.token(), Duration.between(LocalDateTime.now(), login.expiresAt()).getSeconds());
               return Map.of("user", session(login.user()));
           })
           .secure("POST", "/api/auth/logout", c -> {
               auth.logout(c.cookie(Ctx.SESSION_COOKIE), c.user().orElse(null), c.ip());
               c.clearSession();
               return Map.of("ok", true);
           })
           .secure("POST", "/api/auth/password", c -> {
               User u = c.user().orElseThrow(AuthException::notLoggedIn);
               auth.changePassword(u, c.form().get("current"), c.form().get("next"), c.ip());
               return Map.of("ok", true, "message", "Password changed");
           });
    }

    private void limit(Ctx c, String action) throws AuthException {
        if (!limiter.allow(action + ":" + c.ip())) {
            throw new AuthException(429, "RATE_LIMITED", "Too many attempts. Please wait a few minutes and try again.");
        }
    }

    /** What the browser needs to know about the logged-in user (incl. what they may do). */
    static Map<String, Object> session(User u) {
        Map<String, Object> m = Json.of(u);
        m.put("canManageUsers", u.isAdmin());
        m.put("canRunMatch", u.getRole().atLeast(Role.COORDINATOR));
        m.put("canVerify", u.getRole() == Role.OFFICER || u.getRole().atLeast(Role.COORDINATOR));
        return m;
    }
}
