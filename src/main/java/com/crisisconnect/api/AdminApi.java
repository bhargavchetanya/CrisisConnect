package com.crisisconnect.api;

import com.crisisconnect.exception.AuthException;
import com.crisisconnect.exception.EntityNotFoundException;
import com.crisisconnect.exception.ValidationException;
import com.crisisconnect.model.EmergencyRequest;
import com.crisisconnect.model.NotificationChannel;
import com.crisisconnect.model.Organization;
import com.crisisconnect.model.OrganizationType;
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
import com.crisisconnect.service.CrisisService;
import com.crisisconnect.service.NotificationService;
import com.crisisconnect.util.CityDirectory;
import com.crisisconnect.util.Validator;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /api/admin/* - the web console.
 * Coordinators: requests, resources, organizations, outbox. Administrators additionally: users and audit log.
 */
final class AdminApi {

    private final CrisisService crisis;
    private final AuthService auth;
    private final NotificationService notifications;
    private final AuditService audit;

    AdminApi(CrisisService crisis, AuthService auth, NotificationService notifications, AuditService audit) {
        this.crisis = crisis;
        this.auth = auth;
        this.notifications = notifications;
        this.audit = audit;
    }

    void register(Router api) {
        // ------------------------------------------------ overview
        api.secure("GET", "/api/admin/overview", c -> {
            User u = c.require(Role.OFFICER);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("user", AuthApi.session(u));
            m.put("stats", Json.of(crisis.stats()));
            m.put("pendingUsers", u.isAdmin() ? auth.pendingCount()
                    : u.getRole() == Role.COORDINATOR ? (int) auth.listOfficers().stream()
                            .filter(x -> x.getStatus() == UserStatus.PENDING).count() : 0);
            m.put("toVerify", crisis.requests().stream()
                    .filter(r -> r.getVerification() == Verification.UNVERIFIED && r.getStatus().isActive())
                    .filter(r -> u.getRole() != Role.OFFICER || r.getCity().equalsIgnoreCase(u.getCity()))
                    .count());
            m.put("messages", notifications.count());
            m.put("emailMode", notifications.emailEnabled() ? "real (Brevo)" : "simulated");
            m.put("whatsappMode", notifications.whatsappEnabled()
                    ? "real (Twilio) to " + notifications.realWhatsAppNumbers().size() + " number(s)" : "simulated");
            m.put("smsMode", notifications.smsEnabled()
                    ? "real (CircuitDigest) to " + notifications.smsNumbers().size() + " area number(s)" : "simulated");
            m.put("recentMessages", !u.getRole().atLeast(Role.COORDINATOR) ? List.of() : Json.list(notifications.outbox(null, 6), Json::of));
            return m;
        });

        // ------------------------------------------------ requests
        api.secure("GET", "/api/admin/requests", c -> {
            User u = c.require(Role.OFFICER);                  // organizations can see what is needed (read-only)
            List<EmergencyRequest> list = crisis.requests();
            if (u.getRole() == Role.OFFICER) {                 // officers: their own city only
                list = list.stream().filter(r -> r.getCity().equalsIgnoreCase(u.getCity())).toList();
            }
            return Json.list(list, Json::admin);
        }).secure("POST", "/api/admin/requests/{id}/verify", c -> {
            User u = c.require(Role.OFFICER);
            EmergencyRequest r = requireVerifier(u, c.pathInt("id"));
            r = crisis.verifyRequest(r.getId(), u.getEmail(), c.field("note"));
            audit.log(u.getEmail(), "SOS_VERIFIED", r.getTrackingCode() + " - " + r.getTitle() + " (" + r.getCity() + ")", c.ip());
            return Json.admin(r);
        }).secure("POST", "/api/admin/requests/{id}/reject", c -> {
            User u = c.require(Role.OFFICER);
            EmergencyRequest r = requireVerifier(u, c.pathInt("id"));
            r = crisis.rejectRequest(r.getId(), u.getEmail(), c.field("reason"));
            audit.log(u.getEmail(), "SOS_REJECTED", r.getTrackingCode() + " - " + r.getTitle() + " (" + r.getCity() + "): "
                    + r.getVerificationNote(), c.ip());
            return Json.admin(r);
        }).secure("POST", "/api/admin/requests/{id}/cancel", c -> {
            User u = c.require(Role.COORDINATOR);
            int id = c.pathInt("id");
            crisis.cancelRequest(id);
            audit.log(u.getEmail(), "REQUEST_CANCELLED", "request #" + id, c.ip());
            return Map.of("ok", true);
        }).secure("POST", "/api/admin/requests/{id}/urgency", c -> {
            User u = c.require(Role.COORDINATOR);
            int id = c.pathInt("id");
            UrgencyLevel level = UrgencyLevel.valueOf(c.field("urgency"));
            EmergencyRequest r = crisis.changeUrgency(id, level);
            audit.log(u.getEmail(), "URGENCY_CHANGED", r.getTrackingCode() + " -> " + level, c.ip());
            return Json.admin(r);
        });

        // ------------------------------------------------ resources
        api.secure("POST", "/api/admin/resources", c -> {
            User u = c.require(Role.ORGANIZATION);
            Integer orgId = c.optionalInt("organizationId");
            if (u.getRole() == Role.ORGANIZATION) orgId = u.getOrganizationId();     // always their own
            if (orgId == null && u.getOrganizationId() != null && !u.isAdmin()) orgId = u.getOrganizationId();
            if (orgId == null) throw new ValidationException("Please choose an organization");
            AuthService.requireOrgScope(u, orgId);
            int qty = Validator.parseIntOr(c.field("quantity"), 0);
            var loc = CityDirectory.find(c.field("city")).orElse(null);
            if (c.field("type").isEmpty()) throw new ValidationException("Please choose a resource type");
            Resource r = ResourceFactory.create(ResourceType.valueOf(c.field("type")), orgId, c.field("name"), qty, loc);
            String expiry = c.field("expiry");
            if (!expiry.isEmpty()) {
                try { r.setExpiryDate(LocalDate.parse(expiry)); }
                catch (DateTimeParseException e) { throw new ValidationException("Expiry date must be YYYY-MM-DD"); }
            }
            crisis.addResource(r);
            audit.log(u.getEmail(), "RESOURCE_ADDED", qty + " " + r.getUnit() + " of " + r.getName() + " (" + r.getCity() + ")", c.ip());
            if (u.getRole() == Role.ORGANIZATION) notifications.resourceOffered(u, crisis.resource(r.getId()).orElse(r), qty, false);
            c.status(201);
            return Json.of(crisis.resource(r.getId()).orElse(r));
        }).secure("POST", "/api/admin/resources/{id}/restock", c -> {
            User u = c.require(Role.ORGANIZATION);
            Resource r = resource(c.pathInt("id"));
            AuthService.requireOrgScope(u, r.getOrganizationId());
            int qty = Validator.parseIntOr(c.field("quantity"), 0);
            crisis.restock(r.getId(), qty);
            audit.log(u.getEmail(), "RESOURCE_RESTOCKED", "+" + qty + " " + r.getUnit() + " " + r.getName(), c.ip());
            if (u.getRole() == Role.ORGANIZATION) notifications.resourceOffered(u, r, qty, true);
            return Json.of(resource(r.getId()));
        }).secure("DELETE", "/api/admin/resources/{id}", c -> {
            User u = c.require(Role.ORGANIZATION);
            Resource r = resource(c.pathInt("id"));
            AuthService.requireOrgScope(u, r.getOrganizationId());
            crisis.deleteResource(r.getId());
            audit.log(u.getEmail(), "RESOURCE_DELETED", r.getName() + " (" + r.getOrganizationName() + ")", c.ip());
            return Map.of("ok", true);
        });

        // ------------------------------------------------ organizations
        api.secure("POST", "/api/admin/organizations", c -> {
            User u = c.require(Role.COORDINATOR);
            var loc = CityDirectory.find(c.field("city")).orElse(null);
            Organization o = new Organization();
            o.setName(c.field("name"));
            o.setType(c.field("type").isEmpty() ? null : OrganizationType.valueOf(c.field("type")));
            o.setContactPhone(c.field("phone"));
            o.setEmail(c.field("email"));
            o.setLocation(loc);
            crisis.registerOrganization(o);
            audit.log(u.getEmail(), "ORGANIZATION_ADDED", o.getName() + " (" + o.getCity() + ")", c.ip());
            c.status(201);
            return Json.of(o);
        }).secure("DELETE", "/api/admin/organizations/{id}", c -> {
            User u = c.require(Role.ADMIN);
            int id = c.pathInt("id");
            String name = crisis.organization(id).map(Organization::getName).orElse("#" + id);
            crisis.deleteOrganization(id);
            audit.log(u.getEmail(), "ORGANIZATION_DELETED", name, c.ip());
            return Map.of("ok", true);
        });

        // ------------------------------------------------ outbox (SMS / WhatsApp / e-mail)
        api.secure("GET", "/api/admin/outbox", c -> {
            c.require(Role.COORDINATOR);
            String ch = c.query("channel");
            NotificationChannel channel = ch == null || ch.isBlank() || "ALL".equals(ch) ? null : NotificationChannel.valueOf(ch);
            int limit = Validator.parseIntOr(c.query("limit"), 100);
            return Json.list(notifications.outbox(channel, limit), Json::of);
        });

        api.secure("POST", "/api/admin/notifications/test", c -> {
            User u = c.require(Role.ADMIN);
            var sent = notifications.sendTest(u.getEmail());
            audit.log(u.getEmail(), "TEST_NOTIFICATIONS", sent.size() + " message(s)", c.ip());
            notifications.flush();                                    // wait for Twilio / Brevo answers
            return Json.list(notifications.outbox(null, Math.max(1, sent.size())), Json::of);
        });

        // ------------------------------------------------ users (ADMIN)
        api.secure("GET", "/api/admin/users", c -> {
            User u = c.require(Role.COORDINATOR);              // coordinators see (and approve) verification officers only
            return Json.list(u.isAdmin() ? auth.listUsers() : auth.listOfficers(), Json::of);
        }).secure("POST", "/api/admin/users/{id}/approve", c -> {
            User admin = c.require(Role.COORDINATOR);
            return Json.of(auth.approve(admin, c.pathInt("id"), c.ip()));
        }).secure("POST", "/api/admin/users/{id}/status", c -> {
            User admin = c.require(Role.COORDINATOR);
            return Json.of(auth.setStatus(admin, c.pathInt("id"), UserStatus.valueOf(c.field("status")), c.ip()));
        }).secure("POST", "/api/admin/users/{id}/role", c -> {
            User admin = c.require(Role.ADMIN);
            return Json.of(auth.setRole(admin, c.pathInt("id"), Role.valueOf(c.field("role")), c.ip()));
        }).secure("POST", "/api/admin/users/{id}/organization", c -> {
            User admin = c.require(Role.ADMIN);
            return Json.of(auth.setOrganization(admin, c.pathInt("id"), c.optionalInt("organizationId"), c.ip()));
        }).secure("DELETE", "/api/admin/users/{id}", c -> {
            User admin = c.require(Role.COORDINATOR);
            auth.deleteUser(admin, c.pathInt("id"), c.ip());
            return Map.of("ok", true);
        });

        // ------------------------------------------------ audit log (ADMIN)
        api.secure("GET", "/api/admin/audit", c -> {
            c.require(Role.ADMIN);
            return Json.list(audit.recent(Validator.parseIntOr(c.query("limit"), 150)), Json::of);
        });
    }

    /** Officers may verify only open SOS requests of their own city; coordinators and administrators any. */
    private EmergencyRequest requireVerifier(User u, int id) throws EntityNotFoundException, AuthException {
        EmergencyRequest r = crisis.request(id).orElseThrow(() -> new EntityNotFoundException("Request", id));
        if (u.getRole() == Role.OFFICER && !r.getCity().equalsIgnoreCase(u.getCity() == null ? "" : u.getCity())) {
            throw AuthException.forbidden("You verify SOS requests for " + u.getCity() + " only.");
        }
        if (u.getRole() == Role.ORGANIZATION) throw AuthException.forbidden("Organizations cannot verify SOS requests.");
        return r;
    }

    private Resource resource(int id) throws EntityNotFoundException {
        return crisis.resource(id).orElseThrow(() -> new EntityNotFoundException("Resource", id));
    }
}
