package com.crisisconnect;

import com.crisisconnect.dao.DaoFactory;
import com.crisisconnect.db.DatabaseManager;
import com.crisisconnect.model.DisasterType;
import com.crisisconnect.model.EmergencyRequest;
import com.crisisconnect.model.Notification;
import com.crisisconnect.model.NotificationChannel;
import com.crisisconnect.model.Organization;
import com.crisisconnect.model.OrganizationType;
import com.crisisconnect.model.RequestStatus;
import com.crisisconnect.model.ResourceFactory;
import com.crisisconnect.model.ResourceType;
import com.crisisconnect.model.UrgencyLevel;
import com.crisisconnect.service.CrisisService;
import com.crisisconnect.service.EmailSender;
import com.crisisconnect.service.MatchingEngine;
import com.crisisconnect.service.NotificationService;
import com.crisisconnect.util.CityDirectory;
import com.crisisconnect.util.TrackingCodes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** SOS tracking IDs and the SMS / WhatsApp simulation, end to end through CrisisService. */
class TrackingNotificationTest {

    @TempDir
    static Path tmp;
    static CrisisService crisis;
    static NotificationService notifications;

    @BeforeAll
    static void setup() {
        DatabaseManager.overrideUrl("jdbc:sqlite:" + tmp.resolve("notify.db"));
        DatabaseManager.getInstance().initSchema();
        crisis = new CrisisService(DaoFactory.organizations(), DaoFactory.resources(), DaoFactory.requests(),
                DaoFactory.allocations(), new MatchingEngine(180));
        notifications = new NotificationService(DaoFactory.notifications(), DaoFactory.organizations(),
                new EmailSender("", "", ""), "https://crisisconnect.example", 60);
        crisis.addNotifier(notifications);
        Organization o = DaoFactory.organizations().save(new Organization("NDRF Test", OrganizationType.GOVERNMENT,
                "+91 172 2700011", "ndrf@gov.in", CityDirectory.require("Patiala")));
        DaoFactory.resources().save(ResourceFactory.create(ResourceType.VEHICLE, o.getId(), "Rescue boats", 10,
                CityDirectory.require("Patiala")));
    }

    private static EmergencyRequest sos(UrgencyLevel u, int qty) throws Exception {
        EmergencyRequest r = new EmergencyRequest("Village cut off", DisasterType.FLOOD, ResourceType.VEHICLE, qty, 300, u,
                CityDirectory.require("Samana"));
        r.setContactPhone("+91 98765 43210");
        return crisis.createRequest(r);
    }

    @Test
    void everySosGetsATrackingCodeAndAnSms() throws Exception {
        EmergencyRequest r = sos(UrgencyLevel.HIGH, 2);
        assertTrue(TrackingCodes.isValid(r.getTrackingCode()));
        assertEquals(r.getId(), crisis.findByTrackingCode(r.getTrackingCode()).orElseThrow().getId());
        List<Notification> msgs = notifications.forRequest(r.getId());
        assertEquals(1, msgs.size());
        assertEquals(NotificationChannel.SMS, msgs.get(0).getChannel());
        assertTrue(msgs.get(0).getMessage().contains(r.getTrackingCode()));
        assertTrue(msgs.get(0).getMessage().contains("https://crisisconnect.example/track.html?code="));
    }

    @Test
    void criticalSosAlertsNearbyOrganizationsOnWhatsApp() throws Exception {
        EmergencyRequest r = sos(UrgencyLevel.CRITICAL, 1);
        assertTrue(notifications.forRequest(r.getId()).stream()
                .anyMatch(n -> n.getChannel() == NotificationChannel.WHATSAPP && n.getEvent().equals("CRITICAL_ALERT")));
    }

    @Test
    void dispatchSendsOrdersAndHelpOnTheWay() throws Exception {
        EmergencyRequest r = sos(UrgencyLevel.CRITICAL, 3);
        crisis.runMatching();
        List<Notification> msgs = notifications.forRequest(r.getId());
        assertTrue(msgs.stream().anyMatch(n -> n.getEvent().equals("DISPATCH_ORDER") && n.getChannel() == NotificationChannel.WHATSAPP));
        assertTrue(msgs.stream().anyMatch(n -> n.getEvent().equals("HELP_ON_THE_WAY") && n.getChannel() == NotificationChannel.SMS));
        assertEquals(RequestStatus.FULFILLED, crisis.findByTrackingCode(r.getTrackingCode()).orElseThrow().getStatus());
    }

    @Test
    void cancellingNotifiesTheReporter() throws Exception {
        EmergencyRequest r = sos(UrgencyLevel.LOW, 50);
        crisis.cancelRequest(r.getId());
        assertTrue(notifications.forRequest(r.getId()).stream().anyMatch(n -> n.getEvent().equals("REQUEST_CLOSED")));
    }
}
