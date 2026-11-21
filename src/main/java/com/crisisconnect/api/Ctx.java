package com.crisisconnect.api;

import com.crisisconnect.exception.AuthException;
import com.crisisconnect.exception.ValidationException;
import com.crisisconnect.model.Role;
import com.crisisconnect.model.User;
import com.crisisconnect.service.AuthService;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-request context handed to every route handler: path parameters, form fields,
 * the logged-in user (from the session cookie), client IP, and helpers to set cookies / status.
 */
public final class Ctx {

    public static final String SESSION_COOKIE = "cc_session";

    final HttpExchange ex;
    final Map<String, String> params;
    private final AuthService auth;
    private Map<String, String> form;
    private Map<String, String> query;
    private Optional<User> user;
    int status = 200;
    final List<String> setCookies = new ArrayList<>();

    Ctx(HttpExchange ex, Map<String, String> params, AuthService auth) {
        this.ex = ex;
        this.params = params;
        this.auth = auth;
    }

    public String method() { return ex.getRequestMethod(); }

    public int pathInt(String name) throws ValidationException {
        try { return Integer.parseInt(params.get(name)); }
        catch (NumberFormatException e) { throw new ValidationException("Invalid id"); }
    }

    public String path(String name) { return params.get(name); }

    /** application/x-www-form-urlencoded body (what the website sends with URLSearchParams). */
    public Map<String, String> form() throws IOException {
        if (form == null) {
            byte[] raw = ex.getRequestBody().readNBytes(64 * 1024);       // 64 KB cap
            form = parse(new String(raw, StandardCharsets.UTF_8));
        }
        return form;
    }

    public String field(String name) throws IOException {
        String v = form().get(name);
        return v == null ? "" : v.trim();
    }

    public Integer optionalInt(String name) throws IOException, ValidationException {
        String v = field(name);
        if (v.isEmpty() || "null".equals(v)) return null;
        try { return Integer.parseInt(v); }
        catch (NumberFormatException e) { throw new ValidationException(name + " must be a number"); }
    }

    public String query(String name) {
        if (query == null) query = parse(ex.getRequestURI().getRawQuery());
        return query.get(name);
    }

    public String ip() {
        String fwd = ex.getRequestHeaders().getFirst("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        return ex.getRemoteAddress() == null ? "?" : ex.getRemoteAddress().getAddress().getHostAddress();
    }

    public String userAgent() { return ex.getRequestHeaders().getFirst("User-Agent"); }

    public boolean https() {
        return "https".equalsIgnoreCase(ex.getRequestHeaders().getFirst("X-Forwarded-Proto"));
    }

    public String cookie(String name) {
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers == null) return null;
        for (String h : headers) {
            for (String part : h.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals(name)) return kv[1];
            }
        }
        return null;
    }

    public Optional<User> user() {
        if (user == null) user = auth.userForToken(cookie(SESSION_COOKIE));
        return user;
    }

    /** Throws 401/403 unless the caller is logged in with at least this role. */
    public User require(Role role) throws AuthException {
        User u = user().orElse(null);
        AuthService.require(u, role);
        return u;
    }

    public void setSession(String token, long maxAgeSeconds) {
        setCookies.add(SESSION_COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + maxAgeSeconds
                + (https() ? "; Secure" : ""));
    }

    public void clearSession() {
        setCookies.add(SESSION_COOKIE + "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0" + (https() ? "; Secure" : ""));
    }

    public Ctx status(int s) { this.status = s; return this; }

    private static Map<String, String> parse(String body) {
        Map<String, String> map = new HashMap<>();
        if (body == null) return map;
        for (String pair : body.split("&")) {
            if (pair.isBlank()) continue;
            String[] kv = pair.split("=", 2);
            map.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return map;
    }
}
