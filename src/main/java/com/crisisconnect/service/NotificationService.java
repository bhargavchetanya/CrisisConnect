package com.crisisconnect.service;

import com.crisisconnect.dao.NotificationDao;
import com.crisisconnect.dao.OrganizationDao;
import com.crisisconnect.model.EmergencyRequest;
import com.crisisconnect.model.Notification;
import com.crisisconnect.model.NotificationChannel;
import com.crisisconnect.model.Organization;
import com.crisisconnect.model.RequestStatus;
import com.crisisconnect.model.UrgencyLevel;
import com.crisisconnect.util.Phones;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SMS / WhatsApp / e-mail notifications.
 * <p>
 * Listens to {@link CrisisService} through the {@link CrisisNotifier} observer interface and writes every message to
 * the {@code notifications} table - the outbox shown in the web console.
 * <ul>
 *   <li><b>WhatsApp is real</b> (Twilio) for the configured alert numbers and the administrator's number
 *       (WHATSAPP_ALERT_NUMBERS, ADMIN_WHATSAPP) once TWILIO_ACCOUNT_SID / TWILIO_AUTH_TOKEN are set; messages to other
 *       numbers (e.g. demo organizations) are simulated so nobody unknown is contacted.</li>
 *   <li><b>E-mail is real</b> through Brevo when BREVO_API_KEY / EMAIL_FROM are set.</li>
 *   <li><b>SMS is simulated</b> (Indian SMS needs DLT registration); a reporter whose phone is an alert number also
 *       gets the same update on WhatsApp.</li>
 * </ul>
 * Real deliveries run on a background thread, so a slow gateway never delays an SOS or a dispatch.
 *
 * <pre>
 *  Event                       Channel            Recipient
 *  SOS raised                  WhatsApp + e-mail  alert numbers + administrator: disaster, city, need, contact
 *  SOS raised                  SMS                reporter (tracking ID + link)
 *  CRITICAL SOS                WhatsApp           organizations within 60 km
 *  Smart Match dispatch        WhatsApp           each supplying organization (dispatch order)
 *  Smart Match dispatch        SMS                reporter ("help is on the way")
 *  Smart Match finished        WhatsApp + e-mail  administrator (summary)
 *  Organization registered     WhatsApp + e-mail  administrator
 *  Coordinator asks to join    WhatsApp + e-mail  administrator
 *  Request closed              SMS                reporter
 * </pre>
 */
public class NotificationService implements CrisisNotifier {

    private static final Logger LOG = Logger.getLogger(NotificationService.class.getName());

    private final NotificationDao dao;
    private final OrganizationDao orgDao;
    private final EmailSender email;
    private final WhatsAppGateway whatsapp;
    private final String publicUrl;
    private final double criticalRadiusKm;
    private final List<String> alertNumbers;      // E.164, receive every SOS alert
    private final String adminPhone;              // E.164 or null, receives everything for the administrator
    private final List<String> adminEmails;
    private final boolean whatsappToAnyNumber;    // true only with an approved (non-sandbox) WhatsApp sender
    private final ExecutorService outbox = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "notification-outbox");
        t.setDaemon(true);
        return t;
    });

    /** Simulation only (tests, desktop app without gateways). */
    public NotificationService(NotificationDao dao, OrganizationDao orgDao, EmailSender email,
                               String publicUrl, double criticalRadiusKm) {
        this(dao, orgDao, email, new WhatsAppGateway("", "", "", ""), publicUrl, criticalRadiusKm,
                List.of(), null, List.of(), false);
    }

    public NotificationService(NotificationDao dao, OrganizationDao orgDao, EmailSender email, WhatsAppGateway whatsapp,
                               String publicUrl, double criticalRadiusKm, List<String> alertNumbers, String adminPhone,
                               List<String> adminEmails, boolean whatsappToAnyNumber) {
        this.dao = dao;
        this.orgDao = orgDao;
        this.email = email;
        this.whatsapp = whatsapp;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        this.criticalRadiusKm = criticalRadiusKm;
        this.alertNumbers = alertNumbers.stream().map(Phones::toE164).filter(java.util.Objects::nonNull).distinct().toList();
        this.adminPhone = Phones.toE164(adminPhone);
        this.adminEmails = List.copyOf(adminEmails);
        this.whatsappToAnyNumber = whatsappToAnyNumber;
    }

    public String trackingUrl(String code) { return publicUrl + "/track.html?code=" + code; }

    public String publicUrl() { return publicUrl; }

    public boolean emailEnabled() { return email.isConfigured(); }

    public boolean whatsappEnabled() { return whatsapp.isConfigured(); }

    /** Numbers that get real WhatsApp messages (alert list + administrator). */
    public List<String> realWhatsAppNumbers() {
        Set<String> s = new LinkedHashSet<>(alertNumbers);
        if (adminPhone != null) s.add(adminPhone);
        return new ArrayList<>(s);
    }

    private boolean isRealRecipient(String phone) {
        if (!whatsapp.isConfigured()) return false;
        String e = Phones.toE164(phone);
        return e != null && (whatsappToAnyNumber || realWhatsAppNumbers().contains(e));
    }

    // =====================================================================
    //  Domain events (observer callbacks)
    // =====================================================================
    @Override
    public void requestCreated(EmergencyRequest r) {
        // 1) the reporter: tracking ID
        if (hasPhone(r.getContactPhone())) {
            reporterUpdate(r, String.format(
                    "CrisisConnect: SOS received - \"%s\" (%s). Your tracking ID is %s. Priority score %.1f. Track live: %s",
                    r.getTitle(), r.getCity(), r.getTrackingCode(), r.priorityScore(), trackingUrl(r.getTrackingCode())),
                    "SOS_RECEIVED");
        }
        // 2) every alert number + the administrator: disaster, city, need and contact
        String alert = String.format("""
                🚨 *New SOS %s* — %s
                *Disaster:* %s
                *City:* %s
                *Need:* %d %s (%s)
                *People affected:* %,d%s
                *Priority score:* %.1f
                *Reporter:* %s
                Details: %s
                Console: %s/admin.html#requests""",
                r.getTrackingCode(), r.getUrgency(), r.getDisasterType().getLabel(), r.getCity(), r.getQuantityNeeded(),
                r.getResourceType().getUnit(), r.getResourceType().getLabel(), r.getPeopleAffected(),
                r.isVulnerableGroups() ? " · children/elderly present" : "", r.priorityScore(),
                hasPhone(r.getContactPhone()) ? r.getContactPhone() : "no phone given",
                r.getTitle() + " — " + trackingUrl(r.getTrackingCode()), publicUrl);
        Set<String> recipients = new LinkedHashSet<>(alertNumbers);
        if (adminPhone != null) recipients.add(adminPhone);
        for (String phone : recipients) {
            whatsapp(phone, phone.equals(adminPhone) ? "Administrator" : "Alert team", alert, "SOS_ALERT", r.getId());
        }
        adminEmail("New SOS " + r.getTrackingCode() + ": " + r.getDisasterType().getLabel() + " in " + r.getCity()
                + " (" + r.getUrgency() + ")", alert.replace("*", ""), "SOS_ALERT");
        // 3) CRITICAL: organizations nearby are asked to mobilise
        if (r.getUrgency() == UrgencyLevel.CRITICAL) {
            for (Organization o : orgDao.findAll()) {
                if (!hasPhone(o.getContactPhone())) continue;
                double km = o.getLocation().distanceTo(r.getLocation());
                if (km > criticalRadiusKm) continue;
                whatsapp(o.getContactPhone(), o.getName(), String.format(
                        "🚨 CRITICAL SOS %.0f km from you (%s): %s - %s. Needs %d %s of %s, %,d people affected%s. "
                                + "Coordinators, please review: %s/admin.html",
                        km, r.getCity(), r.getDisasterType().getLabel(), r.getTitle(), r.remainingQuantity(),
                        r.getResourceType().getUnit(), r.getResourceType().getLabel(), r.getPeopleAffected(),
                        r.isVulnerableGroups() ? " (children/elderly present)" : "", publicUrl),
                        "CRITICAL_ALERT", r.getId());
            }
        }
    }

    @Override
    public void resourcesDispatched(EmergencyRequest r, List<AllocationPlan> dispatches) {
        if (dispatches.isEmpty()) return;
        for (AllocationPlan p : dispatches) {
            orgDao.findById(p.resource().getOrganizationId()).ifPresent(o -> {
                if (!hasPhone(o.getContactPhone())) return;
                whatsapp(o.getContactPhone(), o.getName(), String.format(
                        "📦 Dispatch order %s: send %d %s of \"%s\" from %s to %s for \"%s\" (%s). "
                                + "Distance %s, ETA ~%d min. Contact on site: %s",
                        r.getTrackingCode(), p.quantity(), p.resource().getUnit(), p.resource().getName(),
                        p.resource().getCity(), r.getCity(), r.getTitle(), r.getDisasterType().getLabel(),
                        km(p.distanceKm()), eta(p.distanceKm()),
                        hasPhone(r.getContactPhone()) ? r.getContactPhone() : "not provided"),
                        "DISPATCH_ORDER", r.getId());
            });
        }
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
            reporterUpdate(r, "CrisisConnect " + r.getTrackingCode() + ": help is on the way - "
                    + String.join("; ", parts) + ". " + tail + " Track: " + trackingUrl(r.getTrackingCode()), "HELP_ON_THE_WAY");
        }
    }

    @Override
    public void matchCompleted(MatchReport report) {
        if (report.allocationsCreated() == 0 && report.unmet() == 0) return;
        String text = "✅ *Smart Match finished*\n" + report.headline()
                + "\nOutbox and dispatch orders: " + publicUrl + "/admin.html#outbox";
        adminAlert("Smart Match: " + report.headline(), text, "MATCH_SUMMARY");
    }

    @Override
    public void organizationRegistered(Organization o) {
        String text = String.format("🏥 *New organization joined*\n%s (%s) - %s\nPhone: %s · E-mail: %s\nReview: %s/admin.html#organizations",
                o.getName(), o.getType(), o.getCity(), blank(o.getContactPhone()), blank(o.getEmail()), publicUrl);
        adminAlert("New organization: " + o.getName() + " (" + o.getCity() + ")", text, "ORG_REGISTERED");
    }

    @Override
    public void requestCancelled(EmergencyRequest r) {
        if (!hasPhone(r.getContactPhone())) return;
        reporterUpdate(r, "CrisisConnect " + r.getTrackingCode() + ": your request \"" + r.getTitle()
                + "\" was closed by a coordinator. If you still need help, raise a new SOS or call 112.", "REQUEST_CLOSED");
    }

    // =====================================================================
    //  Account messages (used by AuthService)
    // =====================================================================
    /** Sends the verification code; the outbox stores a masked copy so admins never see live codes. */
    public void verificationCode(String to, String name, String code) {
        String text = "Hi " + firstName(name) + ", your CrisisConnect verification code is " + code
                + ". It expires in 10 minutes. If you did not sign up, ignore this e-mail.";
        emailMessage(to, name, "Your CrisisConnect verification code", text, text.replace(code, "••••••"), "VERIFY_CODE");
    }

    public void accountApproved(String to, String name, String role) {
        String text = "Hi " + firstName(name) + ", your CrisisConnect " + role.toLowerCase()
                + " account has been approved. You can now log in at " + publicUrl + "/login.html";
        emailMessage(to, name, "Your CrisisConnect account is approved", text, text, "ACCOUNT_APPROVED");
    }

    /** A coordinator / organization asked to join: tell the administrator on WhatsApp and by e-mail. */
    public void newSignupForAdmins(String applicant, String applicantEmail, String org, String via) {
        String text = "👤 *New coordinator request*\n" + applicant + " (" + applicantEmail + ")"
                + (org == null ? "" : "\nOrganization: " + org) + "\nSigned up with: " + via
                + "\nWaiting for your approval: " + publicUrl + "/admin.html#users";
        adminAlert("New coordinator waiting for approval: " + applicant, text, "NEW_SIGNUP");
    }

    /** Admin button "Send test": proves WhatsApp and e-mail are configured correctly. */
    public List<Notification> sendTest(String requestedBy) {
        String text = "✅ CrisisConnect test message requested by " + requestedBy
                + ". If you can read this, alerts are working. " + publicUrl;
        List<Notification> sent = new ArrayList<>();
        for (String phone : realWhatsAppNumbers()) {
            sent.add(whatsapp(phone, phone.equals(adminPhone) ? "Administrator" : "Alert team", text, "TEST", null));
        }
        for (String to : adminEmails) sent.add(emailMessage(to, "Administrator", "CrisisConnect test e-mail", text, text, "TEST"));
        return sent;
    }

    // =====================================================================
    //  Outbox queries
    // =====================================================================
    public List<Notification> outbox(NotificationChannel channel, int limit) {
        return dao.findRecent(channel, Math.max(1, Math.min(limit, 300)));
    }

    public List<Notification> forRequest(int requestId) { return dao.findByRequest(requestId); }

    public int count() { return dao.count(); }

    /** Waits until queued real deliveries are done (tests, shutdown). */
    public void flush() {
        try { outbox.submit(() -> { }).get(30, TimeUnit.SECONDS); }
        catch (Exception e) { LOG.fine("flush interrupted: " + e); }
    }

    // =====================================================================
    //  Delivery
    // =====================================================================
    private void adminAlert(String subject, String whatsappText, String event) {
        if (adminPhone != null) whatsapp(adminPhone, "Administrator", whatsappText, event, null);
        adminEmail(subject, whatsappText.replace("*", ""), event);
    }

    private void adminEmail(String subject, String text, String event) {
        for (String to : adminEmails) emailMessage(to, "Administrator", subject, text, text, event);
    }

    /** Reporter updates: simulated SMS, plus a real WhatsApp copy when the reporter's number is an alert number. */
    private void reporterUpdate(EmergencyRequest r, String text, String event) {
        deliverSimulated(new Notification(NotificationChannel.SMS, r.getContactPhone().trim(), "SOS reporter", text, event, r.getId()));
        if (isRealRecipient(r.getContactPhone())) whatsapp(r.getContactPhone(), "SOS reporter", text, event, r.getId());
    }

    private Notification whatsapp(String phone, String name, String text, String event, Integer requestId) {
        Notification n = new Notification(NotificationChannel.WHATSAPP, phone.trim(), name, text, event, requestId);
        if (!isRealRecipient(phone)) return deliverSimulated(n);
        n.setStatus(Notification.Status.QUEUED);
        save(n);
        outbox.submit(() -> update(n, whatsapp.send(phone, text)));
        return n;
    }

    private Notification emailMessage(String to, String name, String subject, String realText, String storedText, String event) {
        Notification n = new Notification(NotificationChannel.EMAIL, to, name, subject + " — " + storedText, event, null);
        if (!email.isConfigured()) {
            LOG.info("[email:simulated] to=" + to + " | " + subject + " | " + realText);
            n.setStatus(Notification.Status.SIMULATED);
            save(n);
            return n;
        }
        n.setStatus(Notification.Status.QUEUED);
        save(n);
        outbox.submit(() -> update(n, email.sendDetailed(to, name, subject, realText)));
        return n;
    }

    /**
     * Simulated gateway: the message is recorded (and logged) instead of being sent to a phone network.
     * Real WhatsApp goes through {@link WhatsAppGateway} (Twilio) for the configured numbers.
     */
    private Notification deliverSimulated(Notification n) {
        n.setStatus(Notification.Status.SIMULATED);
        LOG.info("[" + n.getChannel() + ":simulated] to=" + n.getRecipient() + " | " + n.getMessage());
        save(n);
        return n;
    }

    private void update(Notification n, WhatsAppGateway.Result res) {
        n.setStatus(res.ok() ? Notification.Status.SENT : Notification.Status.FAILED);
        n.setError(res.ok() ? null : res.detail());
        try {
            if (n.getId() != null) dao.updateStatus(n.getId(), n.getStatus(), n.getError());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not update notification status", e);
        }
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

    private static String blank(String s) { return s == null || s.isBlank() ? "—" : s; }

    private static String firstName(String name) {
        return name == null || name.isBlank() ? "there" : name.trim().split("\\s+")[0];
    }
}
