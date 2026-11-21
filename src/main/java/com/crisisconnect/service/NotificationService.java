package com.crisisconnect.service;

import com.crisisconnect.dao.NotificationDao;
import com.crisisconnect.dao.OrganizationDao;
import com.crisisconnect.model.EmergencyRequest;
import com.crisisconnect.model.Notification;
import com.crisisconnect.model.NotificationChannel;
import com.crisisconnect.model.Organization;
import com.crisisconnect.model.RequestStatus;
import com.crisisconnect.model.UrgencyLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SMS / WhatsApp alert simulation (+ optional real e-mail).
 * <p>
 * Listens to {@link CrisisService} through the {@link CrisisNotifier} observer interface and writes every
 * message to the {@code notifications} table - the "outbox" shown in the web console. SMS and WhatsApp are
 * <b>simulated</b> (a real deployment would plug an SMS/WhatsApp gateway such as Twilio in {@link #deliver});
 * e-mails are really sent through Brevo when an API key is configured.
 *
 * <pre>
 *  Event                      Channel    Recipient                       Message
 *  SOS raised                 SMS        requester's phone               tracking ID + link
 *  CRITICAL SOS raised        WhatsApp   organizations within 60 km      alert to mobilise
 *  Resources dispatched       WhatsApp   each supplying organization     dispatch order (qty, route, ETA)
 *  Resources dispatched       SMS        requester's phone               "help is on the way" + ETA
 *  Request cancelled          SMS        requester's phone               closure notice
 *  Account events             E-mail     user / administrators           code, approval, new sign-up
 * </pre>
 */
public class NotificationService implements CrisisNotifier {

    private static final Logger LOG = Logger.getLogger(NotificationService.class.getName());

    private final NotificationDao dao;
    private final OrganizationDao orgDao;
    private final EmailSender email;
    private final String publicUrl;
    private final double criticalRadiusKm;

    public NotificationService(NotificationDao dao, OrganizationDao orgDao, EmailSender email,
                               String publicUrl, double criticalRadiusKm) {
        this.dao = dao;
        this.orgDao = orgDao;
        this.email = email;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        this.criticalRadiusKm = criticalRadiusKm;
    }

    public String trackingUrl(String code) { return publicUrl + "/track.html?code=" + code; }

    public String publicUrl() { return publicUrl; }

    public boolean emailEnabled() { return email.isConfigured(); }

    // =====================================================================
    //  Domain events (observer callbacks)
    // =====================================================================
    @Override
    public void requestCreated(EmergencyRequest r) {
        if (hasPhone(r.getContactPhone())) {
            sms(r.getContactPhone(), "SOS reporter", String.format(
                    "CrisisConnect: SOS received - \"%s\" (%s). Your tracking ID is %s. Priority score %.1f. Track live: %s",
                    r.getTitle(), r.getCity(), r.getTrackingCode(), r.priorityScore(), trackingUrl(r.getTrackingCode())),
                    "SOS_RECEIVED", r.getId());
        }
        if (r.getUrgency() == UrgencyLevel.CRITICAL) {
            for (Organization o : orgDao.findAll()) {
                if (!hasPhone(o.getContactPhone())) continue;
                double km = o.getLocation().distanceTo(r.getLocation());
                if (km > criticalRadiusKm) continue;
                whatsapp(o.getContactPhone(), o.getName(), String.format(
                        "🚨 CRITICAL SOS %.0f km from you (%s): %s. Needs %d %s of %s, %,d people affected%s. "
                                + "Coordinators, please review: %s/admin.html",
                        km, r.getCity(), r.getTitle(), r.remainingQuantity(), r.getResourceType().getUnit(),
                        r.getResourceType().getLabel(), r.getPeopleAffected(),
                        r.isVulnerableGroups() ? " (children/elderly present)" : "", publicUrl),
                        "CRITICAL_ALERT", r.getId());
            }
        }
    }

    @Override
    public void resourcesDispatched(EmergencyRequest r, List<AllocationPlan> dispatches) {
        if (dispatches.isEmpty()) return;
        // 1) a dispatch order to every supplying organization
        for (AllocationPlan p : dispatches) {
            orgDao.findById(p.resource().getOrganizationId()).ifPresent(o -> {
                if (!hasPhone(o.getContactPhone())) return;
                whatsapp(o.getContactPhone(), o.getName(), String.format(
                        "📦 Dispatch order %s: send %d %s of \"%s\" from %s to %s for \"%s\". "
                                + "Distance %s, ETA ~%d min. Contact on site: %s",
                        r.getTrackingCode(), p.quantity(), p.resource().getUnit(), p.resource().getName(),
                        p.resource().getCity(), r.getCity(), r.getTitle(), km(p.distanceKm()), eta(p.distanceKm()),
                        hasPhone(r.getContactPhone()) ? r.getContactPhone() : "not provided"),
                        "DISPATCH_ORDER", r.getId());
            });
        }
        // 2) one combined "help is on the way" SMS to the person who raised the SOS
        if (hasPhone(r.getContactPhone())) {
            Map<String, int[]> byOrg = new LinkedHashMap<>();     // org -> {qty, minEta}
            for (AllocationPlan p : dispatches) {
                String org = p.resource().getOrganizationName() == null ? p.resource().getName() : p.resource().getOrganizationName();
                int[] v = byOrg.computeIfAbsent(org, k -> new int[]{0, Integer.MAX_VALUE});
                v[0] += p.quantity();
                v[1] = Math.min(v[1], eta(p.distanceKm()));
            }
            List<String> parts = new ArrayList<>();
            byOrg.forEach((org, v) -> parts.add(v[0] + " " + r.getResourceType().getUnit() + " from " + org + " (ETA ~" + v[1] + " min)"));
            String tail = r.getStatus() == RequestStatus.FULFILLED
                    ? "Your request is now fully covered."
                    : "Still needed: " + r.remainingQuantity() + " " + r.getResourceType().getUnit() + " - we are searching.";
            sms(r.getContactPhone(), "SOS reporter", "CrisisConnect " + r.getTrackingCode() + ": help is on the way - "
                    + String.join("; ", parts) + ". " + tail + " Track: " + trackingUrl(r.getTrackingCode()),
                    "HELP_ON_THE_WAY", r.getId());
        }
    }

    @Override
    public void requestCancelled(EmergencyRequest r) {
        if (!hasPhone(r.getContactPhone())) return;
        sms(r.getContactPhone(), "SOS reporter", "CrisisConnect " + r.getTrackingCode() + ": your request \"" + r.getTitle()
                + "\" was closed by a coordinator. If you still need help, raise a new SOS or call 112.",
                "REQUEST_CLOSED", r.getId());
    }

    // =====================================================================
    //  Account e-mails (used by AuthService)
    // =====================================================================
    /** Sends the verification code; the outbox stores a masked copy so admins never see live codes. */
    public void verificationCode(String to, String name, String code) {
        String text = "Hi " + firstName(name) + ", your CrisisConnect verification code is " + code
                + ". It expires in 10 minutes. If you did not sign up, ignore this e-mail.";
        emailMessage(to, name, "Your CrisisConnect verification code", text,
                text.replace(code, "••••••"), "VERIFY_CODE");
    }

    public void accountApproved(String to, String name, String role) {
        String text = "Hi " + firstName(name) + ", your CrisisConnect " + role.toLowerCase()
                + " account has been approved. You can now log in at " + publicUrl + "/login.html";
        emailMessage(to, name, "Your CrisisConnect account is approved", text, text, "ACCOUNT_APPROVED");
    }

    public void newSignupForAdmins(List<String> adminEmails, String applicant, String applicantEmail, String org) {
        for (String admin : adminEmails) {
            String text = applicant + " (" + applicantEmail + ")" + (org == null ? "" : " from " + org)
                    + " verified their e-mail and is waiting for approval as a coordinator. Review: " + publicUrl + "/admin.html#users";
            emailMessage(admin, "Administrator", "New coordinator waiting for approval", text, text, "NEW_SIGNUP");
        }
    }

    // =====================================================================
    //  Outbox queries
    // =====================================================================
    public List<Notification> outbox(NotificationChannel channel, int limit) {
        return dao.findRecent(channel, Math.max(1, Math.min(limit, 300)));
    }

    public List<Notification> forRequest(int requestId) { return dao.findByRequest(requestId); }

    public int count() { return dao.count(); }

    // =====================================================================
    //  Delivery
    // =====================================================================
    private void sms(String phone, String name, String text, String event, Integer requestId) {
        deliver(new Notification(NotificationChannel.SMS, phone.trim(), name, text, event, requestId));
    }

    private void whatsapp(String phone, String name, String text, String event, Integer requestId) {
        deliver(new Notification(NotificationChannel.WHATSAPP, phone.trim(), name, text, event, requestId));
    }

    private void emailMessage(String to, String name, String subject, String realText, String storedText, String event) {
        Notification n = new Notification(NotificationChannel.EMAIL, to, name, subject + " — " + storedText, event, null);
        if (email.isConfigured()) {
            n.setStatus(email.send(to, name, subject, realText) ? Notification.Status.SENT : Notification.Status.FAILED);
        } else {
            LOG.info("[email:simulated] to=" + to + " | " + subject + " | " + realText);
        }
        save(n);
    }

    /**
     * Simulated gateway: the message is recorded (and logged) instead of being sent to a phone network.
     * Swap this method's body for a Twilio / WhatsApp Business API call to go live.
     */
    private void deliver(Notification n) {
        n.setStatus(Notification.Status.SIMULATED);
        LOG.info("[" + n.getChannel() + ":simulated] to=" + n.getRecipient() + " | " + n.getMessage());
        save(n);
    }

    private void save(Notification n) {
        try {
            dao.save(n);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not store notification", e);     // a failed alert must not break a dispatch
        }
    }

    private static boolean hasPhone(String p) { return p != null && p.replaceAll("[^0-9]", "").length() >= 7; }

    private static int eta(double km) { return com.crisisconnect.model.Allocation.etaFor(km); }   // same rule as the UI

    private static String km(double d) { return d < 1 ? "same city" : String.format("%.1f km", d); }

    private static String firstName(String name) {
        return name == null || name.isBlank() ? "there" : name.trim().split("\\s+")[0];
    }
}
