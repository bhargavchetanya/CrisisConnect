package com.crisisconnect.api;

import com.crisisconnect.event.CrisisEvent;
import com.crisisconnect.event.EventBus;
import com.crisisconnect.exception.ValidationException;
import com.crisisconnect.model.DisasterType;
import com.crisisconnect.model.EmergencyRequest;
import com.crisisconnect.model.ResourceType;
import com.crisisconnect.model.UrgencyLevel;
import com.crisisconnect.service.CrisisService;
import com.crisisconnect.util.CityDirectory;
import com.crisisconnect.util.Validator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lightweight REST API + static web server built on the JDK's com.sun.net.httpserver.
 * This is the "enterprise web" face of CrisisConnect - the public website talks to it.
 *
 *   GET  /api/stats          dashboard numbers
 *   GET  /api/requests       all emergency requests
 *   GET  /api/queue          active requests in priority order
 *   GET  /api/resources      all resources
 *   GET  /api/organizations  all organizations
 *   GET  /api/allocations    dispatch history
 *   GET  /api/events         live activity feed
 *   GET  /api/meta           cities + enum values for the website form
 *   POST /api/requests       raise a new SOS (form-urlencoded)
 *   POST /api/match          run the matching engine
 *   GET  /                   serves the website folder
 */
public class ApiServer {

    private static final Logger LOG = Logger.getLogger(ApiServer.class.getName());

    private final CrisisService service;
    private final int port;
    private final Path webRoot;
    private HttpServer server;

    public ApiServer(CrisisService service, int port, Path webRoot) {
        this.service = service;
        this.port = port;
        this.webRoot = webRoot;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/stats", ex -> json(ex, 200, Json.of(service.stats())));
        server.createContext("/api/queue", ex -> json(ex, 200, Json.list(service.priorityQueueSnapshot(), Json::of)));
        server.createContext("/api/resources", ex -> json(ex, 200, Json.list(service.resources(), Json::of)));
        server.createContext("/api/organizations", ex -> json(ex, 200, Json.list(service.organizations(), Json::of)));
        server.createContext("/api/allocations", ex -> json(ex, 200, Json.list(service.allocations(), Json::of)));
        server.createContext("/api/events", this::events);
        server.createContext("/api/meta", this::meta);
        server.createContext("/api/requests", this::requests);
        server.createContext("/api/match", this::match);
        server.createContext("/", this::staticFiles);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        LOG.info("REST API + website running at http://localhost:" + port + "/");
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            LOG.info("API server stopped");
        }
    }

    // ---------------- handlers ----------------
    private void requests(HttpExchange ex) throws IOException {
        if ("OPTIONS".equals(ex.getRequestMethod())) { json(ex, 204, null); return; }
        if ("POST".equals(ex.getRequestMethod())) {
            Map<String, String> f = parseForm(ex.getRequestBody());
            try {
                EmergencyRequest r = new EmergencyRequest(
                        f.getOrDefault("title", ""),
                        DisasterType.valueOf(f.getOrDefault("disasterType", "OTHER")),
                        ResourceType.valueOf(f.getOrDefault("resourceType", "FOOD")),
                        Validator.parseIntOr(f.get("quantity"), 0),
                        Validator.parseIntOr(f.get("people"), 0),
                        UrgencyLevel.valueOf(f.getOrDefault("urgency", "MEDIUM")),
                        CityDirectory.find(f.get("city")).orElse(null));
                r.setContactPhone(f.get("phone"));
                r.setVulnerableGroups("true".equals(f.get("vulnerable")) || "on".equals(f.get("vulnerable")));
                r.setSource("WEB");
                service.createRequest(r);
                json(ex, 201, Json.of(r));
            } catch (ValidationException e) {
                json(ex, 400, Map.of("errors", e.getErrors()));
            } catch (IllegalArgumentException e) {
                json(ex, 400, Map.of("errors", List.of("Invalid value: " + e.getMessage())));
            }
            return;
        }
        json(ex, 200, Json.list(service.requests(), Json::of));
    }

    private void match(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            json(ex, "OPTIONS".equals(ex.getRequestMethod()) ? 204 : 405, Map.of("error", "Use POST"));
            return;
        }
        json(ex, 200, Json.of(service.runMatching()));
    }

    private void events(HttpExchange ex) throws IOException {
        List<Map<String, Object>> out = EventBus.get().recent().stream().map((CrisisEvent e) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", e.type().name());
            m.put("message", e.message());
            m.put("time", e.time().toString());
            return m;
        }).toList();
        json(ex, 200, out);
    }

    private void meta(HttpExchange ex) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cities", CityDirectory.cityNames());
        m.put("disasterTypes", List.of(DisasterType.values()).stream().map(Enum::name).toList());
        m.put("resourceTypes", List.of(ResourceType.values()).stream().map(Enum::name).toList());
        m.put("urgencyLevels", List.of(UrgencyLevel.values()).stream().map(Enum::name).toList());
        json(ex, 200, m);
    }

    private void staticFiles(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        Path file = webRoot.resolve(path.substring(1)).normalize();
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
        byte[] bytes = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    // ---------------- helpers ----------------
    private static void json(HttpExchange ex, int status, Object body) throws IOException {
        try {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            if (body == null) {
                ex.sendResponseHeaders(status, -1);
                return;
            }
            byte[] bytes = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "API error", e);
            throw e;
        } finally {
            ex.close();
        }
    }

    private static Map<String, String> parseForm(InputStream in) throws IOException {
        String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> map = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isBlank()) continue;
            String[] kv = pair.split("=", 2);
            map.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return map;
    }
}
