package com.crisisconnect.api;

import com.crisisconnect.AppContext;
import com.crisisconnect.event.CrisisEvent;
import com.crisisconnect.event.EventBus;
import com.crisisconnect.exception.AuthException;
import com.crisisconnect.exception.EntityNotFoundException;
import com.crisisconnect.exception.ValidationException;
import com.crisisconnect.model.Allocation;
import com.crisisconnect.model.DisasterType;
import com.crisisconnect.model.EmergencyRequest;
import com.crisisconnect.model.Notification;
import com.crisisconnect.model.NotificationChannel;
import com.crisisconnect.model.OrganizationType;
import com.crisisconnect.model.RequestStatus;
import com.crisisconnect.model.ResourceType;
import com.crisisconnect.model.Role;
import com.crisisconnect.model.UrgencyLevel;
import com.crisisconnect.model.UserStatus;
import com.crisisconnect.model.Verification;
import com.crisisconnect.service.AuditService;
import com.crisisconnect.service.AuthService;
import com.crisisconnect.service.CrisisService;
import com.crisisconnect.service.GoogleOAuth;
import com.crisisconnect.service.NotificationService;
import com.crisisconnect.util.CityDirectory;
import com.crisisconnect.util.Security;
import com.crisisconnect.util.TrackingCodes;
import com.crisisconnect.util.Validator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * REST API + static web server built on the JDK's com.sun.net.httpserver.
 *
 * <pre>
 * PUBLIC
 *   GET  /api/stats | /api/queue | /api/requests | /api/resources | /api/organizations | /api/allocations
 *   GET  /api/events | /api/meta
 *   POST /api/requests                raise an SOS -> returns its tracking ID
 *   GET  /api/track/{code}            public SOS tracking (status, dispatches, SMS sent)
 * ACCOUNTS (AuthApi)
 *   POST /api/auth/register | verify | resend | login | logout | password      GET /api/auth/me
 * COORDINATOR or ADMIN
 *   POST /api/match                   run the Smart Match engine
 *   /api/admin/...                    console: requests, resources, organizations, outbox (AdminApi)
 * ADMIN only
 *   /api/admin/users..., /api/admin/audit, DELETE /api/admin/organizations/{id}
 * </pre>
 */
public class ApiServer {

    private static final Logger LOG = Logger.getLogger(ApiServer.class.getName());

    private final CrisisService service;
    private final AuthService auth;
    private final NotificationService notifications;
    private final AuditService audit;
    private final GoogleOAuth google;
    private final int port;
    private final Path webRoot;
    private final RateLimiter sosLimiter = new RateLimiter(15, 10 * 60_000L);
    private HttpServer server;

    /** Kept for the JavaFX app: pulls the other services from the AppContext. */
    public ApiServer(CrisisService service, int port, Path webRoot) {
        this(service, AppContext.get().auth(), AppContext.get().notifications(), AppContext.get().audit(),
                AppContext.get().google(), port, webRoot);
    }

    public ApiServer(CrisisService service, AuthService auth, NotificationService notifications, AuditService audit,
                     GoogleOAuth google, int port, Path webRoot) {
        this.google = google;
        this.service = service;
        this.auth = auth;
        this.notifications = notifications;
        this.audit = audit;
        this.port = port;
        this.webRoot = webRoot;
    }

    public void start() throws IOException {
        Router api = new Router(auth);
        // ---------------- public ----------------
        api.open("GET", "/api/stats", c -> Json.of(service.stats()))
           .open("GET", "/api/queue", c -> Json.list(service.priorityQueueSnapshot(), Json::of))
           .open("GET", "/api/requests", c -> Json.list(service.requests(), Json::of))
           .open("POST", "/api/requests", this::createSos)
           .open("GET", "/api/resources", c -> Json.list(service.resources(), Json::of))
           .open("GET", "/api/organizations", c -> Json.list(service.organizations(), Json::of))
           .open("GET", "/api/allocations", c -> Json.list(service.allocations(), Json::of))
           .open("GET", "/api/events", c -> events())
           .open("GET", "/api/meta", c -> meta())
           .open("GET", "/api/track/{code}", this::track);
        // ---------------- coordinators ----------------
        api.secure("POST", "/api/match", c -> {
            var u = c.require(Role.COORDINATOR);
            var report = service.runMatching(r -> AuthService.inArea(u, r.getCity()));     // a coordinator: his area only
            audit.log(u.getEmail(), "RUN_MATCH", (u.isAdmin() || u.getCity() == null ? "all areas" : u.getCity()) + ": "
                    + report.headline(), c.ip());
            return Json.of(report);
        });
        new AuthApi(auth, audit, google, notifications.publicUrl(), service).register(api);
        new AdminApi(service, auth, notifications, audit).register(api);

        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/", api::handle);
        server.createContext("/", this::staticFiles);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        LOG.info("REST API + website running at http://localhost:" + port + "/");
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            LOG.info("API server stopped");
        }
    }

    // ---------------- public handlers ----------------
    private Object createSos(Ctx c) throws Exception {
        if (!sosLimiter.allow("sos:" + c.ip())) {
            throw new AuthException(429, "RATE_LIMITED", "Too many SOS requests from this network. Please call 112 if this is urgent.");
        }
        Map<String, String> f = c.form();
        EmergencyRequest r = new EmergencyRequest(
                f.getOrDefault("title", ""),
                DisasterType.valueOf(f.getOrDefault("disasterType", "OTHER")),
                ResourceType.valueOf(f.getOrDefault("resourceType", "FOOD")),
                Validator.parseIntOr(f.get("quantity"), 0),
                Validator.parseIntOr(f.get("people"), 0),
                UrgencyLevel.valueOf(f.getOrDefault("urgency", "MEDIUM")),
                CityDirectory.find(f.get("city")).orElse(null));
        if (r.getTitle().length() > 120) throw new ValidationException("Title must be at most 120 characters");
        r.setContactPhone(f.get("phone"));
        r.setVulnerableGroups("true".equals(f.get("vulnerable")) || "on".equals(f.get("vulnerable")));
        r.setSource("WEB");
        // public SOS: checked by the city's verification officer first; staff-raised SOS are trusted
        boolean staff = c.user().map(u -> u.getRole().atLeast(Role.COORDINATOR) && u.getStatus() == UserStatus.ACTIVE).orElse(false);
        if (!staff && (r.getContactPhone() == null || r.getContactPhone().replaceAll("[^0-9]", "").length() < 10)) {
            throw new ValidationException("Please give a 10-digit phone number - a local officer calls it to verify your SOS");
        }
        r.setVerification(staff ? Verification.VERIFIED : Verification.UNVERIFIED);
        if (staff) {
            r.setVerifiedBy(c.user().get().getEmail());
            r.setVerifiedAt(java.time.LocalDateTime.now());
            r.setVerificationNote("raised by CrisisConnect staff");
        }
        service.createRequest(r);
        if (!r.isVerified()) notifications.askOfficersToVerify(r, auth.activeOfficersFor(r.getCity()));
        audit.log(c.user().map(u -> u.getEmail()).orElse("public"), "SOS_RAISED",
                r.getTrackingCode() + " [" + r.getUrgency() + "] " + r.getTitle() + " - " + r.getCity(), c.ip());
        Map<String, Object> out = Json.of(r);
        out.put("trackingCode", r.getTrackingCode());
        out.put("trackingUrl", notifications.trackingUrl(r.getTrackingCode()));
        out.put("smsSent", r.getContactPhone() != null && !r.getContactPhone().isBlank());
        c.status(201);
        return out;
    }

    /** Public tracking page data. Never exposes the reporter's phone number in full. */
    private Object track(Ctx c) throws Exception {
        String code = TrackingCodes.normalize(c.path("code"));
        EmergencyRequest r = TrackingCodes.isValid(code) ? service.findByTrackingCode(code).orElse(null) : null;
        if (r == null) throw new EntityNotFoundException("SOS", code);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trackingCode", r.getTrackingCode());
        m.put("title", r.getTitle());
        m.put("disasterLabel", r.getDisasterType().getLabel());
        m.put("resourceType", r.getResourceType().name());
        m.put("resourceLabel", r.getResourceType().getLabel());
        m.put("unit", r.getResourceType().getUnit());
        m.put("quantityNeeded", r.getQuantityNeeded());
        m.put("quantityFulfilled", r.getQuantityFulfilled());
        m.put("percent", Math.round(r.fulfilmentPercent()));
        m.put("peopleAffected", r.getPeopleAffected());
        m.put("urgency", r.getUrgency().name());
        m.put("priority", r.priorityScore());
        m.put("city", r.getCity());
        m.put("lat", r.getLocation().getLatitude());
        m.put("lon", r.getLocation().getLongitude());
        m.put("status", r.getStatus().name());
        m.put("verification", r.getVerification().name());
        m.put("verificationLabel", r.getVerification().getLabel());
        m.put("createdAt", r.getCreatedAt().toString());
        m.put("phone", Security.mask(r.getContactPhone()));

        List<EmergencyRequest> queue = service.priorityQueueSnapshot();
        int pos = -1;
        for (int i = 0; i < queue.size(); i++) if (queue.get(i).getId().equals(r.getId())) pos = i + 1;
        m.put("queuePosition", pos > 0 ? pos : null);
        m.put("queueLength", queue.size());

        List<Allocation> allocs = service.allocationsFor(r.getId());
        m.put("dispatches", Json.list(allocs, Json::of));

        List<Notification> msgs = notifications.forRequest(r.getId()).stream()
                .filter(n -> n.getChannel() == NotificationChannel.SMS)          // only messages sent to the reporter
                .toList();
        m.put("messages", Json.list(msgs, Json::publicMessage));

        List<Map<String, Object>> timeline = new ArrayList<>();
        timeline.add(step(r.getCreatedAt().toString(), "received", "SOS received",
                String.format("Priority score %.1f · %s", r.priorityScore(), r.getUrgency())));
        if (r.getVerification() == Verification.UNVERIFIED) {
            timeline.add(step(r.getCreatedAt().toString(), "pending", "Waiting for verification",
                    "A government officer of " + r.getCity() + " will check your SOS - keep your phone reachable"));
        } else if (r.getVerifiedAt() != null && r.getVerification() == Verification.VERIFIED) {
            timeline.add(step(r.getVerifiedAt().toString(), "verified", "Verified",
                    "Confirmed as genuine - relief can now be dispatched"));
        } else if (r.getVerification() == Verification.REJECTED) {
            timeline.add(step(r.getVerifiedAt() == null ? r.getCreatedAt().toString() : r.getVerifiedAt().toString(), "closed",
                    "Could not be verified", "The local officer closed this SOS. Raise a new SOS with correct details or call 112."));
        }
        for (Allocation a : allocs) {
            timeline.add(step(a.getCreatedAt().toString(), "dispatched", "Help dispatched",
                    String.format("%d %s of %s from %s · %s · ETA ~%d min", a.getQuantity(), r.getResourceType().getUnit(),
                            a.getResourceName(), a.getOrganizationName(),
                            a.getDistanceKm() < 1 ? "same city" : String.format("%.1f km", a.getDistanceKm()), a.etaMinutes())));
        }
        if (r.getStatus() == RequestStatus.FULFILLED) {
            String when = allocs.isEmpty() ? r.getCreatedAt().toString() : allocs.get(allocs.size() - 1).getCreatedAt().toString();
            timeline.add(step(when, "fulfilled", "Fully covered", "Everything requested has been dispatched"));
        } else if (r.getStatus() == RequestStatus.CANCELLED && r.getVerification() != Verification.REJECTED) {
            String when = msgs.stream().filter(n -> "REQUEST_CLOSED".equals(n.getEvent())).map(n -> n.getCreatedAt().toString())
                    .findFirst().orElse(r.getCreatedAt().toString());
            timeline.add(step(when, "closed", "Closed by a coordinator", "Raise a new SOS if you still need help"));
        }
        m.put("timeline", timeline);
        return m;
    }

    private static Map<String, Object> step(String time, String kind, String title, String text) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("time", time);
        s.put("kind", kind);
        s.put("title", title);
        s.put("text", text);
        return s;
    }

    private Object events() {
        return EventBus.get().recent().stream().map((CrisisEvent e) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", e.type().name());
            m.put("message", e.message());
            m.put("time", e.time().toString());
            return m;
        }).toList();
    }

    private Object meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cities", CityDirectory.cityNames());
        m.put("disasterTypes", List.of(DisasterType.values()).stream().map(Enum::name).toList());
        m.put("resourceTypes", List.of(ResourceType.values()).stream().map(Enum::name).toList());
        m.put("urgencyLevels", List.of(UrgencyLevel.values()).stream().map(Enum::name).toList());
        m.put("organizationTypes", List.of(OrganizationType.values()).stream().map(Enum::name).toList());
        return m;
    }

    // ---------------- static website ----------------
    private void staticFiles(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";
            if (path.equals("/admin") || path.equals("/login") || path.equals("/track")) path += ".html";
            Path file = webRoot.resolve(path.substring(1)).normalize();
            var h = ex.getResponseHeaders();
            h.set("X-Content-Type-Options", "nosniff");
            h.set("X-Frame-Options", "DENY");
            h.set("Referrer-Policy", "strict-origin-when-cross-origin");
            if (!file.startsWith(webRoot.normalize()) || !Files.isRegularFile(file)) {   // block ../ tricks
                byte[] msg = "404 - not found".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(404, msg.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(msg); }
                return;
            }
            String name = file.getFileName().toString();
            String type = name.endsWith(".html") ? "text/html" : name.endsWith(".css") ? "text/css"
                    : name.endsWith(".js") ? "application/javascript" : name.endsWith(".svg") ? "image/svg+xml"
                    : name.endsWith(".png") ? "image/png" : "application/octet-stream";
            h.set("Content-Type", type + "; charset=utf-8");
            h.set("Cache-Control", name.endsWith(".png") ? "public, max-age=86400" : "no-cache");
            byte[] bytes = Files.readAllBytes(file);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        } finally {
            ex.close();
        }
    }
}
