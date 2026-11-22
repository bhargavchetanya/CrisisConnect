package com.crisisconnect.service;

import com.crisisconnect.util.Phones;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real WhatsApp delivery through the Twilio Messages API (WhatsApp Sandbox or an approved sender).
 * <pre>
 * POST {base}/2010-04-01/Accounts/{AccountSid}/Messages.json
 * Authorization: Basic base64(AccountSid:AuthToken)
 * From=whatsapp:+14155238886&To=whatsapp:+91XXXXXXXXXX&Body=...
 * </pre>
 * Configured with TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN and TWILIO_WHATSAPP_FROM. When they are missing,
 * {@link #isConfigured()} is false and the NotificationService keeps simulating WhatsApp.
 */
public class WhatsAppGateway {

    private static final Logger LOG = Logger.getLogger(WhatsAppGateway.class.getName());
    private static final Pattern ERROR_MSG = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern ERROR_CODE = Pattern.compile("\"code\"\\s*:\\s*(\\d+)");

    /** Outcome of one send: ok, and Twilio's message SID or error text. */
    public record Result(boolean ok, String detail) { }

    private final String accountSid;
    private final String authToken;
    private final String from;
    private final String apiBase;
    private final String contentSid;
    private final java.util.Map<String, String> fromByRecipient = new java.util.HashMap<>();  // trial: one number per tester    // Twilio trial: only pre-approved templates (ContentSid HX...) may be sent
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public WhatsAppGateway(String accountSid, String authToken, String from, String apiBase) {
        this(accountSid, authToken, from, apiBase, "");
    }

    public WhatsAppGateway(String accountSid, String authToken, String from, String apiBase, String contentSid) {
        this.contentSid = contentSid == null ? "" : contentSid.trim();
        this.accountSid = accountSid == null ? "" : accountSid.trim();
        this.authToken = authToken == null ? "" : authToken.trim();
        String f = from == null || from.isBlank() ? "+14155238886" : from.trim();     // Twilio sandbox number
        if (f.startsWith("whatsapp:")) f = f.substring("whatsapp:".length());
        this.from = Phones.toE164(f) == null ? f : Phones.toE164(f);
        this.apiBase = apiBase == null || apiBase.isBlank() ? "https://api.twilio.com" : apiBase.replaceAll("/+$", "");
    }

    /**
     * Twilio trial accounts assign a different WhatsApp number to each verified tester (error 572003 otherwise).
     * Format: "6284974761=+15551234567, 9876543210=+15557654321".
     */
    public WhatsAppGateway fromPerRecipient(String mapping) {
        if (mapping == null) return this;
        for (String pair : mapping.split("[,;]")) {
            String[] kv = pair.split("=");
            if (kv.length != 2) continue;
            String to = Phones.toE164(kv[0].trim());
            String f = kv[1].trim().replace("whatsapp:", "");
            String fe = Phones.toE164(f);
            if (to != null && fe != null) fromByRecipient.put(to, fe);
        }
        return this;
    }

    public String fromFor(String toE164) { return fromByRecipient.getOrDefault(toE164, from); }

    public boolean isConfigured() { return !accountSid.isEmpty() && !authToken.isEmpty(); }

    public String from() { return from; }

    /** True when messages are sent as a fixed Twilio template (trial accounts) instead of free text. */
    public boolean usesTemplate() { return !contentSid.isEmpty(); }

    public String contentSid() { return contentSid; }

    public Result send(String toPhone, String body) {
        if (!isConfigured()) return new Result(false, "WhatsApp gateway not configured");
        String to = Phones.toE164(toPhone);
        if (to == null) return new Result(false, "Invalid phone number: " + toPhone);
        String form = "From=" + enc("whatsapp:" + fromFor(to)) + "&To=" + enc("whatsapp:" + to)
                + (usesTemplate() ? "&ContentSid=" + enc(contentSid) : "&Body=" + enc(body));
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + "/2010-04-01/Accounts/" + accountSid + "/Messages.json"))
                    .timeout(Duration.ofSeconds(12))
                    .header("Authorization", "Basic " + Base64.getEncoder()
                            .encodeToString((accountSid + ":" + authToken).getBytes(StandardCharsets.UTF_8)))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 == 2) {
                Matcher m = Pattern.compile("\"sid\"\\s*:\\s*\"([^\"]+)\"").matcher(res.body());
                return new Result(true, m.find() ? m.group(1) : "accepted");
            }
            String detail = describe(res.statusCode(), res.body());
            LOG.warning("Twilio rejected WhatsApp to " + to + ": " + detail);
            return new Result(false, detail);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warning("WhatsApp to " + to + " failed: " + e);
            return new Result(false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Turns Twilio's JSON error into a short hint, e.g. code 63015 = number has not joined the sandbox. */
    static String describe(int status, String body) {
        Matcher m = ERROR_MSG.matcher(body == null ? "" : body);
        Matcher c = ERROR_CODE.matcher(body == null ? "" : body);
        String code = c.find() ? c.group(1) : String.valueOf(status);
        String msg = m.find() ? m.group(1) : "HTTP " + status;
        String hint = switch (code) {
            case "63015", "21608" -> " (this number has not joined the Twilio WhatsApp sandbox - send the join code first)";
            case "63016" -> " (24-hour window closed - the number must send any message to the sandbox number again)";
            case "20003" -> " (wrong TWILIO_ACCOUNT_SID / TWILIO_AUTH_TOKEN)";
            case "572003" -> " (Twilio trial: this tester has its own WhatsApp number - add it to TWILIO_WHATSAPP_FROM_MAP)";
            case "21654" -> " (Twilio trial: set TWILIO_CONTENT_SID to one of the trial templates)";
            default -> "";
        };
        return "Twilio " + code + ": " + msg + hint;
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
