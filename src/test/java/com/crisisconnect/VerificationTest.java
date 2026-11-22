package com.crisisconnect;

import com.crisisconnect.dao.DaoFactory;
import com.crisisconnect.db.DatabaseManager;
import com.crisisconnect.exception.AuthException;
import com.crisisconnect.exception.ValidationException;
import com.crisisconnect.model.DisasterType;
import com.crisisconnect.model.EmergencyRequest;
import com.crisisconnect.model.Organization;
import com.crisisconnect.model.OrganizationType;
import com.crisisconnect.model.RequestStatus;
import com.crisisconnect.model.Resource;
import com.crisisconnect.model.ResourceFactory;
import com.crisisconnect.model.ResourceType;
import com.crisisconnect.model.Role;
import com.crisisconnect.model.UrgencyLevel;
import com.crisisconnect.model.User;
import com.crisisconnect.model.UserStatus;
import com.crisisconnect.model.Verification;
import com.crisisconnect.service.AuditService;
import com.crisisconnect.service.AuthService;
import com.crisisconnect.service.CrisisNotifier;
import com.crisisconnect.service.CrisisService;
import com.crisisconnect.service.EmailSender;
import com.crisisconnect.service.MatchingEngine;
import com.crisisconnect.service.NotificationService;
import com.crisisconnect.util.CityDirectory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** SOS verification by city officers: officer accounts, approval rules, and Smart Match skipping unverified SOS. */
class VerificationTest {

    @TempDir
    static Path tmp;
    static AuthService auth;
    static CrisisService crisis;
    static User admin;
    static final List<String> verifiedEvents = new CopyOnWriteArrayList<>();
    static final String ADMIN = "admin@crisis.test";

    @BeforeAll
    static void setup() throws Exception {
        DatabaseManager.overrideUrl("jdbc:sqlite:" + tmp.resolve("verify.db"));
        DatabaseManager.getInstance().initSchema();
        NotificationService ns = new NotificationService(DaoFactory.notifications(), DaoFactory.organizations(),
                new EmailSender("", "", ""), "http://localhost:8080", 60);
        auth = new AuthService(DaoFactory.users(), DaoFactory.organizations(), ns,
                new AuditService(DaoFactory.audit()), List.of(ADMIN), 24, true);
        auth.bootstrapAdmins("AdminPass#2026");
        admin = auth.login(ADMIN, "AdminPass#2026", "ip", "ua").user();
        crisis = new CrisisService(DaoFactory.organizations(), DaoFactory.resources(), DaoFactory.requests(),
                DaoFactory.allocations(), new MatchingEngine(180));
        crisis.addNotifier(new CrisisNotifier() {
            @Override public void requestVerified(EmergencyRequest r) { verifiedEvents.add(r.getTrackingCode()); }
        });
        Organization org = crisis.registerOrganization(new Organization("Patiala Food Bank", OrganizationType.NGO,
                "9000000300", "food@x.in", CityDirectory.require("Patiala")));
        Resource rice = ResourceFactory.create(ResourceType.FOOD, org.getId(), "Rice packets", 500, CityDirectory.require("Patiala"));
        crisis.addResource(rice);
    }

    private static EmergencyRequest publicSos(String title) throws Exception {
        EmergencyRequest r = new EmergencyRequest(title, DisasterType.FLOOD, ResourceType.FOOD, 40, 100,
                UrgencyLevel.HIGH, CityDirectory.require("Patiala"));
        r.setSource("WEB");
        r.setVerification(Verification.UNVERIFIED);
        return crisis.createRequest(r);
    }

    @Test
    void smartMatchWaitsForVerification() throws Exception {
        EmergencyRequest r = publicSos("Village cut off by flood");
        crisis.runMatching();
        EmergencyRequest stored = crisis.request(r.getId()).orElseThrow();
        assertEquals(Verification.UNVERIFIED, stored.getVerification());
        assertEquals(0, stored.getQuantityFulfilled(), "unverified SOS must not receive resources");

        crisis.verifyRequest(r.getId(), "officer@punjab.gov.in", "called the reporter");
        assertTrue(verifiedEvents.contains(r.getTrackingCode()), "verification fires the alert event");
        crisis.runMatching();
        stored = crisis.request(r.getId()).orElseThrow();
        assertEquals(Verification.VERIFIED, stored.getVerification());
        assertEquals("officer@punjab.gov.in", stored.getVerifiedBy());
        assertEquals(RequestStatus.FULFILLED, stored.getStatus());

        assertThrows(ValidationException.class, () -> crisis.verifyRequest(r.getId(), "x@y.in", null), "only once");
    }

    @Test
    void rejectedSosIsClosedAndNeedsAReason() throws Exception {
        EmergencyRequest r = publicSos("Prank call");
        assertThrows(ValidationException.class, () -> crisis.rejectRequest(r.getId(), "officer@punjab.gov.in", " "));
        crisis.rejectRequest(r.getId(), "officer@punjab.gov.in", "Reporter admitted it was a prank");
        EmergencyRequest stored = crisis.request(r.getId()).orElseThrow();
        assertEquals(Verification.REJECTED, stored.getVerification());
        assertEquals(RequestStatus.CANCELLED, stored.getStatus());
        assertEquals("Reporter admitted it was a prank", stored.getVerificationNote());
    }

    @Test
    void officerIsApprovedOnceByACoordinatorAndThenLogsIn() throws Exception {
        User officer = auth.registerOfficer("Harpreet Singh", "tehsildar@punjab.gov.in", "Verify#2026",
                "patiala", "Tehsildar, Revenue Department", "ip");
        assertEquals(Role.OFFICER, officer.getRole());
        assertEquals("Patiala", officer.getCity());
        assertEquals(UserStatus.PENDING, officer.getStatus());
        AuthException pending = assertThrows(AuthException.class,
                () -> auth.login("tehsildar@punjab.gov.in", "Verify#2026", "ip", "ua"));
        assertEquals("PENDING_APPROVAL", pending.getCode());
        assertTrue(auth.activeOfficersFor("Patiala").isEmpty(), "pending officers get no SOS e-mails");

        // a coordinator may approve officers ...
        User coordinator = new User("coord@crisis.test", "Coord", "x");
        coordinator.setRole(Role.COORDINATOR);
        coordinator.setStatus(UserStatus.ACTIVE);
        coordinator.setId(9999);
        auth.approve(coordinator, officer.getId(), "ip");
        assertEquals(Role.OFFICER, auth.login("tehsildar@punjab.gov.in", "Verify#2026", "ip", "ua").user().getRole());
        assertEquals(1, auth.activeOfficersFor("PATIALA").size());
        assertTrue(auth.listOfficers().stream().anyMatch(u -> u.getEmail().equals("tehsildar@punjab.gov.in")));

        // ... but not organizations or coordinators
        auth.register("Org Staff", "staff@ngo.in", "Relief2026", crisis.organizations().get(0).getId(), "ip");
        User orgUser = DaoFactory.users().findByEmail("staff@ngo.in").orElseThrow();
        assertThrows(ValidationException.class, () -> auth.approve(coordinator, orgUser.getId(), "ip"));
        auth.approve(admin, orgUser.getId(), "ip");                     // the administrator can
    }

    @Test
    void officerSignUpIsValidated() {
        assertThrows(ValidationException.class,
                () -> auth.registerOfficer("A", "a@gov.in", "Verify#2026", "Atlantis", "Officer", "ip"));
        assertThrows(ValidationException.class,
                () -> auth.registerOfficer("A", "a@gov.in", "Verify#2026", "Patiala", " ", "ip"));
        assertThrows(ValidationException.class,
                () -> auth.registerOfficer("A", ADMIN, "Verify#2026", "Patiala", "Officer", "ip"));
    }

    @Test
    void coordinatorsWorkOnlyInTheirOwnArea() throws Exception {
        User patialaCoord = new User("patiala.coord@crisis.test", "P Coord", "x");
        patialaCoord.setRole(Role.COORDINATOR);
        patialaCoord.setStatus(UserStatus.ACTIVE);
        patialaCoord.setCity("Patiala");
        patialaCoord.setId(9998);
        assertTrue(AuthService.inArea(patialaCoord, "patiala"));
        assertFalse(AuthService.inArea(patialaCoord, "Ludhiana"));
        assertTrue(AuthService.inArea(admin, "Ludhiana"), "administrators cover every area");

        // officers: a Patiala coordinator approves Patiala officers only
        User ludhianaOfficer = auth.registerOfficer("L Officer", "ldh.officer@punjab.gov.in", "Verify#2026",
                "Ludhiana", "Naib Tehsildar", "ip");
        assertThrows(ValidationException.class, () -> auth.approve(patialaCoord, ludhianaOfficer.getId(), "ip"));
        assertTrue(auth.listOfficers(patialaCoord).stream().noneMatch(o -> o.getCity().equals("Ludhiana")));
        auth.approve(admin, ludhianaOfficer.getId(), "ip");
        assertEquals(1, auth.activeOfficersFor("Ludhiana").size(), "Ludhiana SOS e-mails go to the Ludhiana officer");
        assertTrue(auth.activeOfficersFor("Ludhiana").stream().noneMatch(o -> o.getCity().equals("Patiala")));

        // Smart Match run by the Patiala coordinator never serves a Ludhiana SOS
        EmergencyRequest ldh = new EmergencyRequest("Ludhiana flood", DisasterType.FLOOD, ResourceType.FOOD, 10, 40,
                UrgencyLevel.CRITICAL, CityDirectory.require("Ludhiana"));
        crisis.createRequest(ldh);                                           // verified (desktop)
        crisis.runMatching(r -> AuthService.inArea(patialaCoord, r.getCity()));
        assertEquals(0, crisis.request(ldh.getId()).orElseThrow().getQuantityFulfilled());
        crisis.runMatching(r -> AuthService.inArea(admin, r.getCity()));
        assertTrue(crisis.request(ldh.getId()).orElseThrow().getQuantityFulfilled() > 0, "the administrator's run covers it");
    }
}
