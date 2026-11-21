package com.crisisconnect.api;

import com.crisisconnect.exception.AuthException;
import com.crisisconnect.model.User;
import com.crisisconnect.service.AuditService;
import com.crisisconnect.service.AuthService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** /api/auth/* - sign-up with e-mail code, login / logout, current user, change password. */
final class AuthApi {

    private final AuthService auth;
    private final AuditService audit;
    private final RateLimiter limiter = new RateLimiter(30, 15 * 60_000L);   // 30 tries / 15 min / IP / action

    AuthApi(AuthService auth, AuditService audit) {
        this.auth = auth;
        this.audit = audit;
    }

    void register(Router api) {
        api.secure("GET", "/api/auth/me", c -> {
               Map<String, Object> out = new LinkedHashMap<>();          // Map.of() does not allow null values
               out.put("user", c.user().map(AuthApi::session).orElse(null));
               return out;
           })
           .secure("POST", "/api/auth/register", c -> {
               limit(c, "register");
               var reg = auth.register(c.field("name"), c.field("email"), c.form().get("password"),
                       c.optionalInt("organizationId"), c.ip());
               Map<String, Object> out = new LinkedHashMap<>();
               out.put("ok", true);
               out.put("email", reg.user().getEmail());
               out.put("message", "We sent a 6-digit code to " + reg.user().getEmail() + ". Enter it to verify your e-mail.");
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
        m.put("canRunMatch", true);
        return m;
    }
}
