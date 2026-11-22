package com.crisisconnect;

import com.crisisconnect.dao.DaoFactory;
import com.crisisconnect.db.DatabaseManager;
import com.crisisconnect.exception.AuthException;
import com.crisisconnect.exception.ValidationException;
import com.crisisconnect.model.Organization;
import com.crisisconnect.model.OrganizationType;
import com.crisisconnect.model.Role;
import com.crisisconnect.model.User;
import com.crisisconnect.model.UserStatus;
import com.crisisconnect.service.AuditService;
import com.crisisconnect.service.AuthService;
import com.crisisconnect.service.EmailSender;
import com.crisisconnect.service.NotificationService;
import com.crisisconnect.util.CityDirectory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Sign-up, e-mail verification, approval, login, lockout and roles - against a real SQLite file. */
class AuthServiceTest {

    @TempDir
    static Path tmp;
    static AuthService auth;
    static final String ADMIN = "bhargavchetanya47@gmail.com";

    @BeforeAll
    static void setup() {
        DatabaseManager.overrideUrl("jdbc:sqlite:" + tmp.resolve("auth.db"));
        DatabaseManager.getInstance().initSchema();
        NotificationService notifications = new NotificationService(DaoFactory.notifications(), DaoFactory.organizations(),
                new EmailSender("", "", ""), "http://localhost:8080", 60);
        auth = new AuthService(DaoFactory.users(), DaoFactory.organizations(), notifications,
                new AuditService(DaoFactory.audit()), List.of(ADMIN), 24, true);
        auth.bootstrapAdmins("AdminPass#2026");
    }

    @Test
    void adminIsCreatedFromAdminEmails() throws Exception {
        AuthService.Login login = auth.login(ADMIN.toUpperCase(), "AdminPass#2026", "127.0.0.1", "junit");
        assertEquals(Role.ADMIN, login.user().getRole());
        assertEquals(login.user().getId(), auth.userForToken(login.token()).orElseThrow().getId());
        auth.logout(login.token(), login.user(), "127.0.0.1");
        assertTrue(auth.userForToken(login.token()).isEmpty(), "session must be gone after logout");
    }

    @Test
    void signUpNeedsVerificationThenApproval() throws Exception {
        AuthService.Registration reg = auth.register("Memansi", "memansi@thapar.edu", "Relief2026", null, "127.0.0.1");
        assertNotNull(reg.devCode(), "demo mode exposes the code when no e-mail provider is configured");

        AuthException notVerified = assertThrows(AuthException.class,
                () -> auth.login("memansi@thapar.edu", "Relief2026", "ip", "ua"));
        assertEquals("EMAIL_NOT_VERIFIED", notVerified.getCode());

        assertThrows(ValidationException.class, () -> auth.verifyEmail("memansi@thapar.edu", "000000", "ip"));
        User u = auth.verifyEmail("memansi@thapar.edu", reg.devCode(), "ip");
        assertEquals(UserStatus.PENDING, u.getStatus());

        AuthException pending = assertThrows(AuthException.class,
                () -> auth.login("memansi@thapar.edu", "Relief2026", "ip", "ua"));
        assertEquals("PENDING_APPROVAL", pending.getCode());

        User admin = auth.login(ADMIN, "AdminPass#2026", "ip", "ua").user();
        auth.approve(admin, u.getId(), "ip");
        assertEquals(Role.COORDINATOR, auth.login("memansi@thapar.edu", "Relief2026", "ip", "ua").user().getRole());
    }

    @Test
    void weakPasswordsAndDuplicateAdminAreRejected() {
        assertThrows(ValidationException.class, () -> auth.register("A", "a@x.in", "short1", null, "ip"));
        assertThrows(ValidationException.class, () -> auth.register("A", "a@x.in", "onlyletters", null, "ip"));
        assertThrows(ValidationException.class, () -> auth.register("A", ADMIN, "Relief2026", null, "ip"));
    }

    @Test
    void fiveWrongPasswordsLockTheAccount() throws Exception {
        var reg = auth.register("Harschelle", "harschelle@thapar.edu", "Relief2026", null, "ip");
        User u = auth.verifyEmail("harschelle@thapar.edu", reg.devCode(), "ip");
        auth.approve(auth.login(ADMIN, "AdminPass#2026", "ip", "ua").user(), u.getId(), "ip");
        for (int i = 1; i < AuthService.MAX_FAILED_LOGINS; i++) {
            assertEquals("INVALID_CREDENTIALS",
                    assertThrows(AuthException.class, () -> auth.login("harschelle@thapar.edu", "wrong", "ip", "ua")).getCode());
        }
        AuthException locked = assertThrows(AuthException.class, () -> auth.login("harschelle@thapar.edu", "wrong", "ip", "ua"));
        assertEquals(423, locked.getStatus());
        // even the right password is refused while locked
        assertEquals(423, assertThrows(AuthException.class,
                () -> auth.login("harschelle@thapar.edu", "Relief2026", "ip", "ua")).getStatus());
    }

    @Test
    void unknownEmailGivesTheSameErrorAsWrongPassword() {
        AuthException e = assertThrows(AuthException.class, () -> auth.login("nobody@x.in", "Relief2026", "ip", "ua"));
        assertEquals("INVALID_CREDENTIALS", e.getCode());
    }

    @Test
    void rolesAreChecked() throws Exception {
        assertThrows(AuthException.class, () -> AuthService.require(null, Role.COORDINATOR));
        User coord = new User("c@x.in", "C", "h");
        coord.setRole(Role.COORDINATOR);
        AuthService.require(coord, Role.COORDINATOR);
        assertEquals(403, assertThrows(AuthException.class, () -> AuthService.require(coord, Role.ADMIN)).getStatus());
        coord.setOrganizationId(1);
        coord.setOrganizationName("Red Cross");
        AuthService.requireOrgScope(coord, 1);
        assertThrows(AuthException.class, () -> AuthService.requireOrgScope(coord, 2));
    }

    @Test
    void organizationAccountsOnlyManageTheirOwnResourcesAndCannotDispatch() throws Exception {
        Organization mine = new Organization("Patiala Relief Trust", OrganizationType.NGO, "9876500001", "trust@x.in",
                CityDirectory.require("Patiala"));
        Organization other = new Organization("Ludhiana Food Bank", OrganizationType.NGO, "9876500002", "fb@x.in",
                CityDirectory.require("Ludhiana"));
        DaoFactory.organizations().save(mine);
        DaoFactory.organizations().save(other);
        var reg = auth.register("Org Staff", "staff@trust.org", "Relief2026", mine.getId(), "ip");
        assertEquals(Role.ORGANIZATION, reg.user().getRole(), "signing up for an organization gives the Organization role");
        User u = auth.verifyEmail("staff@trust.org", reg.devCode(), "ip");
        auth.approve(auth.login(ADMIN, "AdminPass#2026", "ip", "ua").user(), u.getId(), "ip");
        User staff = auth.login("staff@trust.org", "Relief2026", "ip", "ua").user();

        AuthService.require(staff, Role.ORGANIZATION);                                   // may add / restock
        AuthService.requireOrgScope(staff, mine.getId());                                // own resources: ok
        assertThrows(AuthException.class, () -> AuthService.requireOrgScope(staff, other.getId()));
        assertThrows(AuthException.class, () -> AuthService.require(staff, Role.COORDINATOR),
                "Smart Match / dispatch needs a coordinator or the administrator");

        User unlinked = new User("x@y.in", "X", "h");
        unlinked.setRole(Role.ORGANIZATION);
        assertThrows(AuthException.class, () -> AuthService.requireOrgScope(unlinked, mine.getId()));
    }
}
