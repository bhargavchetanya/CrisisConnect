package com.crisisconnect.service;

import com.crisisconnect.dao.NotificationDao;
import com.crisisconnect.dao.OrganizationDao;
import com.crisisconnect.model.EmergencyRequest;
import com.crisisconnect.model.Notification;
import com.crisisconnect.model.NotificationChannel;
import com.crisisconnect.model.DisasterType;
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
import java.util.concurrent.ConcurrentHashMap;
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
 *   <li><b>Area SMS is real</b> when CIRCUITDIGEST_API_KEY and SMS_ALERT_NUMBERS are set: the people in the affected
 *       area get "The Flood alert is currently active in Patiala. Please ensure safety." for every SOS and
 *       "The relief team is currently on the way to Patiala." when Smart Match dispatches (free CircuitDigest Cloud
 *       templates). Other SMS (reporter updates) stay simulated - Indian free-text SMS needs DLT registration.</li>
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
    private final SmsGateway sms;
    private final List<String> smsNumbers;        // E.164, people in the affected area who get real SMS alerts
    private final Map<String, Long> lastDispatchSms = new ConcurrentHashMap<>();   // city -> time, saves the SMS quota
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
        this(dao, orgDao, email, whatsapp, publicUrl, criticalRadiusKm, alertNumbers, adminPhone, adminEmails,
                whatsappToAnyNumber, new SmsGateway("", "", ""), List.of());
    }

    public NotificationService(NotificationDao dao, OrganizationDao orgDao, EmailSender email, WhatsAppGateway whatsapp,
                               String publicUrl, double criticalRadiusKm, List<String> alertNumbers, String adminPhone,
                               List<String> adminEmails, boolean whatsappToAnyNumber, SmsGateway sms, List<String> smsNumbers) {
        this.sms = sms;
        this.smsNumbers = List.copyOf(smsNumbers);
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

    private volatile boolean simulateSms = true;

    /** Turns the simulated reporter SMS cards off (the web deployment has no SMS channel). */
    public void setSimulateSms(boolean on) { this.simulateSms = on; }

    public boolean smsEnabled() { return sms.isConfigured() && !smsNumbers.isEmpty(); }

    public List<String> smsNumbers() { return smsNumbers; }

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
        if (!r.isVerified()) {
            // public SOS: officers check it first - only an e-mail to the administrator now, WhatsApp after verification
            adminEmail("SOS awaiting verification " + r.getTrackingCode() + ": " + r.getDisasterType().getLabel() + " in "
                    + r.getCity() + " (" + r.getUrgency() + ")", sosDetails(r)
                    + "\nA verification officer of " + r.getCity() + " has been asked to check it: " + publicUrl
                    + "/admin.html#requests", "SOS_PENDING_VERIFICATION");
            return;
        }
        sosAlerts(r, null);
    }

    @Override
    public void requestVerified(EmergencyRequest r) {
        sosAlerts(r, r.getVerifiedBy());
    }

    @Override
    public void requestRejected(EmergencyRequest r) {
        if (!hasPhone(r.getContactPhone())) return;
        reporterUpdate(r, "CrisisConnect " + r.getTrackingCode() + ": your SOS could not be verified by the local officer and was"
                + " closed. If you need help, raise a new SOS with correct details or call 112.", "SOS_REJECTED");
    }

    /** E-mails the verification officers of the SOS's city (no WhatsApp - keeps the trial quota for real alerts). */
    public void askOfficersToVerify(EmergencyRequest r, List<com.crisisconnect.model.User> officers) {
        for (com.crisisconnect.model.User o : officers) {
            String text = "Hi " + firstName(o.getName()) + ",\nA new SOS needs your verification before relief can be dispatched.\n\n"
                    + sosDetails(r) + "\nPlease call the reporter, then verify or reject it here: " + publicUrl + "/admin.html#requests";
            emailMessage(o.getEmail(), o.getName(), "Please verify SOS " + r.getTrackingCode() + " in " + r.getCity(),
                    text, text, "VERIFY_REQUEST");
        }
    }

    private String sosDetails(EmergencyRequest r) {
        return String.format("""
                SOS %s - %s
                Disaster: %s
                City: %s
                Need: %d %s (%s)
                People affected: %,d%s
                Reporter phone: %s
                Details: %s
                """, r.getTrackingCode(), r.getUrgency(), r.getDisasterType().getLabel(), r.getCity(),
                r.getQuantityNeeded(), r.getResourceType().getUnit(), r.getResourceType().getLabel(),
                r.getPeopleAffected(), r.isVulnerableGroups() ? " (children/elderly present)" : "",
                hasPhone(r.getContactPhone()) ? r.getContactPhone() : "not given", r.getTitle());
    }

    /** The real alerts for a genuine SOS: WhatsApp to the alert team + administrator, area SMS, e-mail, nearby orgs. */
    private void sosAlerts(EmergencyRequest r, String verifiedBy) {
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
                r.getTitle() + " — " + trackingUrl(r.getTrackingCode()), publicUrl)
                + (verifiedBy == null ? "" : "\n✅ Verified by " + verifiedBy);
        Set<String> recipients = new LinkedHashSet<>(alertNumbers);
        if (adminPhone != null) recipients.add(adminPhone);
        for (String phone : recipients) {
            whatsapp(phone, phone.equals(adminPhone) ? "Administrator" : "Alert team", alert, "SOS_ALERT", r.getId());
        }
        // people in the affected area: real SMS "The Flood alert is currently active in Patiala. Please ensure safety."
        areaSms(disasterWord(r.getDisasterType()) + " alert", "active in " + r.getCity(), "SOS_AREA_ALERT", r.getId());
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
        // people in the area: "The relief team is currently on the way to Patiala." (once per city per 30 min)
        String cityKey = r.getCity() == null ? "" : r.getCity().trim().toLowerCase();
        long now = System.currentTimeMillis();
        Long last = lastDispatchSms.get(cityKey);
        if (last == null || now - last > 30 * 60_000L) {
            lastDispatchSms.put(cityKey, now);
            areaSms("relief team", "on the way to " + r.getCity(), "RELIEF_AREA_ALERT", r.getId());
        }
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

    /** An organization offered (or restocked) supplies - the administrator decides whether to dispatch them. */
    public void resourceOffered(com.crisisconnect.model.User by, com.crisisconnect.model.Resource r, int qty, boolean restock) {
        String org = r.getOrganizationName() == null ? blank(by.getOrganizationName()) : r.getOrganizationName();
        String text = String.format("📦 *%s %s*\n%s %s of %s in %s\nBy: %s (%s)\nRun Smart Match to dispatch: %s/admin.html#overview",
                org, restock ? "restocked supplies" : "offered new supplies", (restock ? "+" : "") + qty, r.getUnit(), r.getName(),
                blank(r.getCity()), by.getName(), by.getEmail(), publicUrl);
        // organization activity: e-mail only (keeps the small WhatsApp trial quota for SOS alerts)
        adminEmail((restock ? "Restock: " : "New supplies: ") + org + " - " + qty + " " + r.getUnit() + " of " + r.getName(),
                text.replace("*", ""), "RESOURCE_OFFERED");
    }

    @Override
    public void organizationRegistered(Organization o) {
        String text = String.format("🏥 *New organization joined*\n%s (%s) - %s\nPhone: %s · E-mail: %s\nReview: %s/admin.html#organizations",
                o.getName(), o.getType(), o.getCity(), blank(o.getContactPhone()), blank(o.getEmail()), publicUrl);
        adminEmail("New organization: " + o.getName() + " (" + o.getCity() + ")", text.replace("*", ""), "ORG_REGISTERED");
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
        String text = "👤 *New account request*\n" + applicant + " (" + applicantEmail + ")"
                + (org == null ? "" : "\nOrganization: " + org) + "\nSigned up with: " + via
                + "\nWaiting for your approval: " + publicUrl + "/admin.html#users";
        adminEmail("New account waiting for approval: " + applicant, text.replace("*", ""), "NEW_SIGNUP");
    }

    /** Admin button "Send test": proves WhatsApp and e-mail are configured correctly. */
    public List<Notification> sendTest(String requestedBy) {
        String text = "✅ CrisisConnect test message requested by " + requestedBy
                + ". If you can read this, alerts are working. " + publicUrl;
        List<Notification> sent = new ArrayList<>();
        for (String phone : realWhatsAppNumbers()) {
            sent.add(whatsapp(phone, phone.equals(adminPhone) ? "Administrator" : "Alert team", text, "TEST", null));
        }
        sent.addAll(areaSms("CrisisConnect test", "working", "TEST", null));
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
        // SMS is not part of this deployment unless the SMS gateway is configured: no simulated SMS cards
        if (smsEnabled() || simulateSms) {
            deliverSimulated(new Notification(NotificationChannel.SMS, r.getContactPhone().trim(), "SOS reporter", text, event, r.getId()));
        }
        if (isRealRecipient(r.getContactPhone())) whatsapp(r.getContactPhone(), "SOS reporter", text, event, r.getId());
    }

    private Notification whatsapp(String phone, String name, String text, String event, Integer requestId) {
        Notification n = new Notification(NotificationChannel.WHATSAPP, phone.trim(), name, text, event, requestId);
        if (!isRealRecipient(phone)) return deliverSimulated(n);
        if (whatsapp.usesTemplate()) {          // trial: the phone gets Twilio's template, the outbox keeps the full alert
            n = new Notification(NotificationChannel.WHATSAPP, phone.trim(), name,
                    "[Sent as Twilio trial template " + whatsapp.contentSid() + "]\n" + text, event, requestId);
        }
        n.setStatus(Notification.Status.QUEUED);
        save(n);
        final Notification queued = n;
        outbox.submit(() -> update(queued, whatsapp.send(phone, text)));
        return n;
    }

    /** Real SMS (CircuitDigest template) to every area number; simulated when the gateway is not configured. */
    private List<Notification> areaSms(String var1, String var2, String event, Integer requestId) {
        List<Notification> out = new ArrayList<>();
        String text = SmsGateway.preview(var1, var2);
        for (String phone : smsNumbers) {
            Notification n = new Notification(NotificationChannel.SMS, phone, "Resident (area alert)", text, event, requestId);
            if (!sms.isConfigured()) { out.add(deliverSimulated(n)); continue; }
            n.setStatus(Notification.Status.QUEUED);
            save(n);
            outbox.submit(() -> update(n, sms.send(phone, var1, var2)));
            out.add(n);
        }
        return out;
    }

    private static String disasterWord(DisasterType t) {
        return switch (t) {
            case ACCIDENT -> "Accident";
            case MEDICAL_SHORTAGE -> "Medical shortage";
            case OTHER -> "Emergency";
            default -> t.getLabel();
        };
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
