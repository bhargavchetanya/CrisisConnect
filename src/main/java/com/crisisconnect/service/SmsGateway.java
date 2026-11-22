package com.crisisconnect.service;

import com.crisisconnect.util.Phones;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real SMS to Indian numbers through the free CircuitDigest Cloud SMS API (100 SMS / month, up to 5 linked numbers).
 * <pre>
 * POST https://www.circuitdigest.cloud/api/v1/send_sms?ID={templateId}
 * Authorization: {apiKey}
 * {"mobiles":"919876543210","var1":"Flood alert","var2":"active in Patiala"}
 * </pre>
 * The API only sends pre-approved templates with two variables (max 30 letters/digits each). CrisisConnect uses
 * template 108: "The {var1} is currently {var2}. Please ensure safety." Configured with CIRCUITDIGEST_API_KEY and
 * SMS_TEMPLATE_ID; without a key {@link #isConfigured()} is false and SMS stays simulated.
 */
public class SmsGateway {

    private static final Logger LOG = Logger.getLogger(SmsGateway.class.getName());
    private static final Pattern MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]*)\"");
    public static final int MAX_VAR = 30;

    private final String apiKey;
    private final String templateId;
    private final String apiUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public SmsGateway(String apiKey, String templateId, String apiUrl) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.templateId = templateId == null || templateId.isBlank() ? "108" : templateId.trim();
        this.apiUrl = apiUrl == null || apiUrl.isBlank() ? "https://www.circuitdigest.cloud/api/v1/send_sms" : apiUrl.trim();
    }

    public boolean isConfigured() { return !apiKey.isEmpty(); }

    public String templateId() { return templateId; }

    /** Text the phone will show for template 108 (used for the outbox). */
    public static String preview(String var1, String var2) {
        return "The " + clean(var1) + " is currently " + clean(var2) + ". Please ensure safety.";
    }

    /** Letters, digits and single spaces only, at most 30 characters (API rule). */
    public static String clean(String s) {
        String t = (s == null ? "" : s).replaceAll("[^A-Za-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        return t.length() > MAX_VAR ? t.substring(0, MAX_VAR).trim() : t;
    }

    public WhatsAppGateway.Result send(String phone, String var1, String var2) {
        if (!isConfigured()) return new WhatsAppGateway.Result(false, "SMS gateway not configured");
        String e164 = Phones.toE164(phone);
        if (e164 == null) return new WhatsAppGateway.Result(false, "Invalid phone number: " + phone);
        String mobile = e164.substring(1);                                     // 91XXXXXXXXXX
        String json = "{\"mobiles\":\"" + mobile + "\",\"var1\":\"" + clean(var1) + "\",\"var2\":\"" + clean(var2) + "\"}";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(apiUrl + "?ID=" + templateId))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            String body = res.body() == null ? "" : res.body();
            boolean ok = res.statusCode() / 100 == 2 && !body.toLowerCase().contains("\"status\":\"error\"")
                    && !body.toLowerCase().contains("\"status\": \"error\"");
            if (ok) return new WhatsAppGateway.Result(true, "accepted");
            String detail = describe(res.statusCode(), body);
            LOG.warning("CircuitDigest rejected SMS to " + e164 + ": " + detail);
            return new WhatsAppGateway.Result(false, detail);
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warning("SMS to " + e164 + " failed: " + ex);
            return new WhatsAppGateway.Result(false, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    static String describe(int status, String body) {
        Matcher m = MESSAGE.matcher(body == null ? "" : body);
        String msg = m.find() ? m.group(1) : (body == null || body.isBlank() ? "no response body" : body.strip());
        if (msg.length() > 160) msg = msg.substring(0, 160) + "...";
        String hint = switch (status) {
            case 401 -> " (wrong CIRCUITDIGEST_API_KEY)";
            case 403 -> " (monthly limit reached or number not linked in CircuitDigest)";
            default -> "";
        };
        return "SMS HTTP " + status + ": " + msg + hint;
    }
}
