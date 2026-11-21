package com.crisisconnect.api;

import com.crisisconnect.exception.AuthException;
import com.crisisconnect.exception.EntityNotFoundException;
import com.crisisconnect.exception.ValidationException;
import com.crisisconnect.service.AuthService;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal REST router on top of com.sun.net.httpserver: maps "METHOD /api/path/{id}" to a handler,
 * converts exceptions to HTTP status codes and writes JSON.
 * <pre>
 *  ValidationException     -> 400 {"errors": [...]}
 *  AuthException           -> 401 / 403 / 423 / 429 {"error": "...", "code": "..."}
 *  EntityNotFoundException -> 404
 *  anything else           -> 500 (logged)
 * </pre>
 * CSRF protection: every state-changing call that relies on the session cookie must carry the custom
 * header {@code X-CC-Client}. Browsers cannot add custom headers to cross-site form posts, and the API
 * never grants CORS for it, so another website cannot make a logged-in coordinator's browser act.
 */
final class Router {

    private static final Logger LOG = Logger.getLogger(Router.class.getName());

    @FunctionalInterface
    interface Handler { Object handle(Ctx c) throws Exception; }

    private record Route(String method, Pattern pattern, List<String> names, boolean publicCors, Handler handler) { }

    private final List<Route> routes = new ArrayList<>();
    private final AuthService auth;

    Router(AuthService auth) { this.auth = auth; }

    /** Public, read-only data: CORS allowed so the site also works when opened from file:// in demo setups. */
    Router open(String method, String path, Handler h) { return add(method, path, true, h); }

    /** Session-aware route (login, admin...): no CORS, CSRF header required for writes. */
    Router secure(String method, String path, Handler h) { return add(method, path, false, h); }

    private Router add(String method, String path, boolean cors, Handler h) {
        List<String> names = new ArrayList<>();
        Matcher m = Pattern.compile("\\{(\\w+)}").matcher(path);
        StringBuilder regex = new StringBuilder();
        int last = 0;
        while (m.find()) {
            regex.append(Pattern.quote(path.substring(last, m.start()))).append("([^/]+)");
            names.add(m.group(1));
            last = m.end();
        }
        regex.append(Pattern.quote(path.substring(last)));
        routes.add(new Route(method, Pattern.compile("^" + regex + "/?$"), names, cors, h));
        return this;
    }

    void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        try {
            if ("OPTIONS".equals(method)) { write(ex, 204, null, true, List.of()); return; }
            boolean pathExists = false;
            for (Route r : routes) {
                Matcher m = r.pattern().matcher(path);
                if (!m.matches()) continue;
                pathExists = true;
                if (!r.method().equals(method)) continue;
                Map<String, String> params = new HashMap<>();
                for (int i = 0; i < r.names().size(); i++) params.put(r.names().get(i), m.group(i + 1));
                Ctx c = new Ctx(ex, params, auth);
                Object body;
                try {
                    if (!r.publicCors() && !"GET".equals(method) && ex.getRequestHeaders().getFirst("X-CC-Client") == null) {
                        throw new AuthException(403, "CSRF", "Missing X-CC-Client header");
                    }
                    body = r.handler().handle(c);
                } catch (ValidationException e) {
                    c.status = 400;
                    body = Map.of("errors", e.getErrors(), "error", String.join(". ", e.getErrors()));
                } catch (AuthException e) {
                    c.status = e.getStatus();
                    body = Map.of("error", e.getMessage(), "code", e.getCode());
                } catch (EntityNotFoundException e) {
                    c.status = 404;
                    body = Map.of("error", e.getMessage());
                } catch (IllegalArgumentException e) {
                    c.status = 400;
                    body = Map.of("errors", List.of("Invalid value: " + e.getMessage()), "error", "Invalid value: " + e.getMessage());
                }
                if (body instanceof Redirect redirect) {
                    ex.getResponseHeaders().set("Location", redirect.location());
                    ex.getResponseHeaders().set("Cache-Control", "no-store");
                    for (String ck : c.setCookies) ex.getResponseHeaders().add("Set-Cookie", ck);
                    ex.sendResponseHeaders(302, -1);
                    return;
                }
                write(ex, c.status, body, r.publicCors(), c.setCookies);
                return;
            }
            write(ex, pathExists ? 405 : 404, Map.of("error", pathExists ? "Method not allowed" : "Unknown API endpoint"),
                    true, List.of());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "API error on " + method + " " + path, e);
            try { write(ex, 500, Map.of("error", "Internal server error"), true, List.of()); }
            catch (Exception ignored) { /* response already started */ }
        } finally {
            ex.close();
        }
    }

    static void write(HttpExchange ex, int status, Object body, boolean cors, List<String> cookies) throws IOException {
        var h = ex.getResponseHeaders();
        if (cors) {
            h.set("Access-Control-Allow-Origin", "*");
            h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            h.set("Access-Control-Allow-Headers", "Content-Type");
        }
        h.set("Cache-Control", "no-store");
        h.set("X-Content-Type-Options", "nosniff");
        for (String c : cookies) h.add("Set-Cookie", c);
        if (body == null) { ex.sendResponseHeaders(status, -1); return; }
        byte[] bytes = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        h.set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
}
