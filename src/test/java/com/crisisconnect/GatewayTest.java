package com.crisisconnect;

import com.crisisconnect.dao.DaoFactory;
import com.crisisconnect.db.DatabaseManager;
import com.crisisconnect.exception.AuthException;
import com.crisisconnect.model.DisasterType;
import com.crisisconnect.model.EmergencyRequest;
import com.crisisconnect.model.Notification;
import com.crisisconnect.model.NotificationChannel;
import com.crisisconnect.model.Organization;
import com.crisisconnect.model.OrganizationType;
import com.crisisconnect.model.ResourceType;
import com.crisisconnect.model.Role;
import com.crisisconnect.model.UrgencyLevel;
import com.crisisconnect.service.AuditService;
import com.crisisconnect.service.AuthService;
import com.crisisconnect.service.CrisisService;
import com.crisisconnect.service.EmailSender;
import com.crisisconnect.service.GoogleOAuth;
import com.crisisconnect.service.MatchingEngine;
import com.crisisconnect.service.NotificationService;
import com.crisisconnect.service.SmsGateway;
import com.crisisconnect.service.WhatsAppGateway;
import com.crisisconnect.util.CityDirectory;
import com.crisisconnect.util.Phones;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real gateways (Twilio WhatsApp, Brevo e-mail, Google sign-in) tested against a local stub HTTP server,
 * so the exact requests CrisisConnect sends can be checked without internet access or real accounts.
 */
class GatewayTest {

    @TempDir
    static Path tmp;
    static HttpServer stub;
    static String base;
    static final List<String> twilioBodies = new CopyOnWriteArrayList<>();
    static final List<String> twilioAuth = new CopyOnWriteArrayList<>();
    static final List<String> brevoBodies = new CopyOnWriteArrayList<>();
    static final List<String> smsRequests = new CopyOnWriteArrayList<>();
    static final String ADMIN_PHONE = "9000000001", ALERT_PHONE = "9000000002", OTHER_PHONE = "9000000099";

    @BeforeAll
    static void start() throws Exception {
        DatabaseManager.overrideUrl("jdbc:sqlite:" + tmp.resolve("gw.db"));
        DatabaseManager.getInstance().initSchema();
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/2010-04-01/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            twilioBodies.add(URLDecoder.decode(body, StandardCharsets.UTF_8));
            twilioAuth.add(ex.getRequestHeaders().getFirst("Authorization"));
            boolean notJoined = body.contains("9000000002");     // pretend the alert number has not joined the sandbox
            reply(ex, notJoined ? 400 : 201, notJoined
                    ? "{\"code\": 63015, \"message\": \"Channel Sandbox can only send messages to phone numbers that have joined the Sandbox\"}"
                    : "{\"sid\": \"SM123\", \"status\": \"queued\"}");
        });
        stub.createContext("/sms", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            smsRequests.add(ex.getRequestURI().getQuery() + " | " + ex.getRequestHeaders().getFirst("Authorization") + " | " + body);
            boolean unlinked = body.contains("919000000077");
            reply(ex, unlinked ? 403 : 200, unlinked ? "{\"status\":\"error\",\"message\":\"Number not linked\",\"code\":403}"
                    : "{\"status\":\"success\",\"message\":\"SMS sent successfully\"}");
        });
        stub.createContext("/brevo", ex -> {
            brevoBodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(ex, 201, "{\"messageId\":\"<x@brevo>\"}");
        });
        stub.createContext("/token", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean ok = body.contains("code=good-code") && body.contains("code_verifier=") && body.contains("client_secret=sec");
            reply(ex, ok ? 200 : 400, ok ? "{\"access_token\":\"at-1\",\"token_type\":\"Bearer\"}" : "{\"error\":\"invalid_grant\"}");
        });
        stub.createContext("/userinfo", ex -> {
            boolean ok = "Bearer at-1".equals(ex.getRequestHeaders().getFirst("Authorization"));
            reply(ex, ok ? 200 : 401, "{\"sub\":\"1234\",\"email\":\"Relief.Lead@Example.org\",\"email_verified\":true,\"name\":\"Relief Lead\"}");
        });
        stub.start();
        base = "http://127.0.0.1:" + stub.getAddress().getPort();
    }

    @AfterAll
    static void stop() { stub.stop(0); }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int code, String json) throws java.io.IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static NotificationService realNotifications() {
        return new NotificationService(DaoFactory.notifications(), DaoFactory.organizations(),
                new EmailSender("key", "alerts@example.org", "CrisisConnect", base + "/brevo"),
                new WhatsAppGateway("AC123", "tok", "", base), "https://cc.example", 60,
                Phones.parseList(ALERT_PHONE + ", " + ADMIN_PHONE), ADMIN_PHONE, List.of("admin@example.org"), false);
    }

    @Test
    void indianNumbersAreNormalised() {
        assertEquals("+919876543210", Phones.toE164("98765 43210"));
        assertEquals("+919876543210", Phones.toE164("09876543210"));
        assertEquals("+919876543210", Phones.toE164("+91 98765-43210"));
        assertEquals("+919876543210", Phones.toE164("919876543210"));
        assertEquals(List.of("+919000000001", "+919000000002"), Phones.parseList("9000000001, +91 9000000002;9000000001"));
    }

    @Test
    void sosAlertGoesToAlertNumbersAndAdminWithCityAndDisaster() throws Exception {
        twilioBodies.clear();
        NotificationService ns = realNotifications();
        CrisisService crisis = new CrisisService(DaoFactory.organizations(), DaoFactory.resources(), DaoFactory.requests(),
                DaoFactory.allocations(), new MatchingEngine(180));
        crisis.addNotifier(ns);
        DaoFactory.organizations().save(new Organization("Unknown Org", OrganizationType.NGO, OTHER_PHONE, "o@x.in",
                CityDirectory.require("Patiala")));
        EmergencyRequest r = new EmergencyRequest("Bridge collapsed", DisasterType.FLOOD, ResourceType.VEHICLE, 3, 50,
                UrgencyLevel.CRITICAL, CityDirectory.require("Patiala"));
        crisis.createRequest(r);
        ns.flush();

        // real WhatsApp only to the configured numbers, with disaster + city in the text
        assertTrue(twilioBodies.stream().anyMatch(b -> b.contains("To=whatsapp:+919000000001")));
        assertTrue(twilioBodies.stream().anyMatch(b -> b.contains("To=whatsapp:+919000000002")));
        assertTrue(twilioBodies.stream().allMatch(b -> b.contains("From=whatsapp:+14155238886")));
        assertTrue(twilioBodies.stream().noneMatch(b -> b.contains("9000000099")), "unknown organizations stay simulated");
        String admin = twilioBodies.stream().filter(b -> b.contains("+919000000001")).findFirst().orElseThrow();
        assertTrue(admin.contains("Disaster:* Flood") && admin.contains("City:* Patiala") && admin.contains(r.getTrackingCode()));
        assertEquals("Basic " + Base64.getEncoder().encodeToString("AC123:tok".getBytes()), twilioAuth.get(0));

        List<Notification> msgs = ns.forRequest(r.getId());
        Notification toAdmin = msgs.stream().filter(n -> n.getRecipient().equals("+919000000001")).findFirst().orElseThrow();
        Notification toAlert = msgs.stream().filter(n -> n.getRecipient().equals("+919000000002")).findFirst().orElseThrow();
        Notification toOrg = msgs.stream().filter(n -> n.getRecipient().equals(OTHER_PHONE)).findFirst().orElseThrow();
        assertEquals(Notification.Status.SENT, toAdmin.getStatus());
        assertEquals(Notification.Status.FAILED, toAlert.getStatus());
        assertTrue(toAlert.getError().contains("has not joined the Twilio WhatsApp sandbox"), toAlert.getError());
        assertEquals(Notification.Status.SIMULATED, toOrg.getStatus());
        assertTrue(brevoBodies.stream().anyMatch(b -> b.contains("admin@example.org") && b.contains("Patiala")), "admin gets an e-mail");
    }

    @Test
    void googleSignInCreatesPendingCoordinatorAndAlertsAdmin() throws Exception {
        twilioBodies.clear();
        GoogleOAuth google = new GoogleOAuth("cid", "sec", base + "/auth", base + "/token", base + "/userinfo");
        String verifier = GoogleOAuth.newVerifier();
        String url = google.authorizationUrl("https://cc.example/api/auth/google/callback", "st8", verifier);
        assertTrue(url.contains("code_challenge=" + GoogleOAuth.challenge(verifier)) && url.contains("code_challenge_method=S256"));
        assertThrows(Exception.class, () -> google.exchange("bad-code", verifier, "https://cc.example/cb"));

        GoogleOAuth.Profile p = google.exchange("good-code", verifier, "https://cc.example/cb");
        assertEquals("Relief.Lead@Example.org", p.email());
        assertTrue(p.emailVerified());

        NotificationService ns = realNotifications();
        AuthService auth = new AuthService(DaoFactory.users(), DaoFactory.organizations(), ns,
                new AuditService(DaoFactory.audit()), List.of("admin@example.org"), 24, false);
        auth.bootstrapAdmins("AdminPass#2026");
        AuthException pending = assertThrows(AuthException.class, () -> auth.loginWithGoogle(p, "ip", "ua"));
        assertEquals("PENDING_APPROVAL", pending.getCode());
        ns.flush();
        assertTrue(twilioBodies.stream().noneMatch(b -> b.contains("relief.lead@example.org")), "join requests are e-mail only");
        assertTrue(brevoBodies.stream().anyMatch(b -> b.contains("relief.lead@example.org")), "admin e-mail for the join request");

        var admin = auth.login("admin@example.org", "AdminPass#2026", "ip", "ua").user();
        var lead = DaoFactory.users().findByEmail("relief.lead@example.org").orElseThrow();
        auth.approve(admin, lead.getId(), "ip");
        assertEquals(Role.COORDINATOR, auth.loginWithGoogle(p, "ip", "ua").user().getRole());

        // the administrator can also use Google with the same address
        var adminLogin = auth.loginWithGoogle(new GoogleOAuth.Profile("9", "ADMIN@example.org", true, "Admin"), "ip", "ua");
        assertEquals(Role.ADMIN, adminLogin.user().getRole());
        assertThrows(AuthException.class, () -> auth.loginWithGoogle(new GoogleOAuth.Profile("1", "x@y.in", false, "X"), "ip", "ua"));
    }

    @Test
    void testButtonReachesEveryConfiguredNumberAndAdminEmail() {
        twilioBodies.clear();
        brevoBodies.clear();
        NotificationService ns = realNotifications();
        List<Notification> sent = ns.sendTest("admin@example.org");
        ns.flush();
        assertEquals(3, sent.size());                     // 2 WhatsApp + 1 e-mail
        assertEquals(2, twilioBodies.size());
        assertEquals(1, brevoBodies.size());
        assertTrue(ns.outbox(NotificationChannel.EMAIL, 1).get(0).getStatus() == Notification.Status.SENT);
    }

    @Test
    void sosSendsTemplateSmsToPeopleInTheArea() throws Exception {
        smsRequests.clear();
        NotificationService ns = new NotificationService(DaoFactory.notifications(), DaoFactory.organizations(),
                new EmailSender("", "", "CrisisConnect"), new WhatsAppGateway("", "", "", ""), "https://cc.example", 60,
                List.of(), "", List.of(), false, new SmsGateway("cd-key", "108", base + "/sms"),
                Phones.parseList("9000000005, 9000000077"));
        assertTrue(ns.smsEnabled());
        CrisisService crisis = new CrisisService(DaoFactory.organizations(), DaoFactory.resources(), DaoFactory.requests(),
                DaoFactory.allocations(), new MatchingEngine(180));
        crisis.addNotifier(ns);
        EmergencyRequest r = new EmergencyRequest("Houses flooded", DisasterType.FLOOD, ResourceType.FOOD, 40, 120,
                UrgencyLevel.HIGH, CityDirectory.require("Patiala"));
        crisis.createRequest(r);
        ns.flush();
        assertEquals(2, smsRequests.size());
        String first = smsRequests.get(0);
        assertTrue(first.startsWith("ID=108 | cd-key | "), first);
        assertTrue(first.contains("\"mobiles\":\"919000000005\"") && first.contains("\"var1\":\"Flood alert\"")
                && first.contains("\"var2\":\"active in Patiala\""), first);
        List<Notification> sms = ns.forRequest(r.getId()).stream()
                .filter(n -> n.getChannel() == NotificationChannel.SMS && n.getEvent().equals("SOS_AREA_ALERT")).toList();
        assertEquals(2, sms.size());
        Notification ok = sms.stream().filter(n -> n.getRecipient().equals("+919000000005")).findFirst().orElseThrow();
        Notification bad = sms.stream().filter(n -> n.getRecipient().equals("+919000000077")).findFirst().orElseThrow();
        assertEquals(Notification.Status.SENT, ok.getStatus());
        assertEquals("The Flood alert is currently active in Patiala. Please ensure safety.", ok.getMessage());
        assertEquals(Notification.Status.FAILED, bad.getStatus());
        assertTrue(bad.getError().contains("Number not linked"), bad.getError());
        assertEquals("Road Industrial Accident alert", SmsGateway.clean("Road / Industrial Accident alert!"));
        assertTrue(SmsGateway.clean("active in Some Extremely Long City Name").length() <= 30);
    }

    @Test
    void trialTemplateIsSentWhenContentSidIsSet() {
        twilioBodies.clear();
        WhatsAppGateway wa = new WhatsAppGateway("AC123", "tok", "+17372508034", base, "HXabc123");
        assertTrue(wa.send("9000000001", "free text").ok());
        String b = twilioBodies.get(0);
        assertTrue(b.contains("ContentSid=HXabc123") && !b.contains("Body=") && b.contains("From=whatsapp:+17372508034"), b);
    }

    @Test
    void eachTrialTesterCanHaveItsOwnFromNumber() {
        twilioBodies.clear();
        WhatsAppGateway wa = new WhatsAppGateway("AC123", "tok", "+17372508034", base, "HXabc123")
                .fromPerRecipient("6284974761=+15550001111");
        wa.send("6284974761", "x");
        wa.send("9000000001", "x");
        assertTrue(twilioBodies.get(0).contains("From=whatsapp:+15550001111"), twilioBodies.get(0));
        assertTrue(twilioBodies.get(1).contains("From=whatsapp:+17372508034"), twilioBodies.get(1));
    }

    @Test
    void whatsappAlertWaitsUntilTheSosIsVerified() throws Exception {
        twilioBodies.clear();
        brevoBodies.clear();
        NotificationService ns = realNotifications();
        CrisisService crisis = new CrisisService(DaoFactory.organizations(), DaoFactory.resources(), DaoFactory.requests(),
                DaoFactory.allocations(), new MatchingEngine(180));
        crisis.addNotifier(ns);
        EmergencyRequest r = new EmergencyRequest("Roof collapsed", DisasterType.EARTHQUAKE, ResourceType.SHELTER, 10, 30,
                UrgencyLevel.HIGH, CityDirectory.require("Patiala"));
        r.setVerification(com.crisisconnect.model.Verification.UNVERIFIED);
        crisis.createRequest(r);
        ns.flush();
        assertTrue(twilioBodies.stream().noneMatch(b -> b.contains(r.getTrackingCode())), "no WhatsApp before verification");
        assertTrue(brevoBodies.stream().anyMatch(b -> b.contains("awaiting verification") && b.contains(r.getTrackingCode())),
                "the administrator gets an e-mail that it is waiting");

        crisis.verifyRequest(r.getId(), "officer@punjab.gov.in", "confirmed by phone");
        ns.flush();
        assertTrue(twilioBodies.stream().anyMatch(b -> b.contains(r.getTrackingCode()) && b.contains("Verified by officer@punjab.gov.in")),
                "WhatsApp alert goes out once verified");
    }
}
