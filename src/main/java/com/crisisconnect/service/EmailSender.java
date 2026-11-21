package com.crisisconnect.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends real e-mail through Brevo's HTTPS API (same provider as the Partner in Project platform;
 * Render's free tier blocks SMTP ports, so an HTTPS API is used instead of SMTP).
 * When BREVO_API_KEY / EMAIL_FROM are not configured, {@link #isConfigured()} is false and the
 * notification service only records the e-mail in the outbox (simulation mode).
 */
public class EmailSender {

    private static final Logger LOG = Logger.getLogger(EmailSender.class.getName());

    private final String apiKey;
    private final String from;
    private final String fromName;
    private final String apiUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public EmailSender(String apiKey, String from, String fromName) {
        this(apiKey, from, fromName, "https://api.brevo.com/v3/smtp/email");
    }

    /** apiUrl is configurable only so tests can point it at a local stub server. */
    public EmailSender(String apiKey, String from, String fromName, String apiUrl) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.from = from == null ? "" : from.trim();
        this.fromName = fromName == null || fromName.isBlank() ? "CrisisConnect" : fromName.trim();
    }

    public boolean isConfigured() { return !apiKey.isEmpty() && !from.isEmpty(); }

    /** @return true if Brevo accepted the e-mail */
    public boolean send(String to, String toName, String subject, String text) {
        return sendDetailed(to, toName, subject, text).ok();
    }

    public WhatsAppGateway.Result sendDetailed(String to, String toName, String subject, String text) {
        if (!isConfigured()) return new WhatsAppGateway.Result(false, "E-mail not configured");
        String html = "<div style=\"font-family:Arial,sans-serif;max-width:520px;margin:auto;padding:24px;"
                + "background:#0b1020;color:#e7ecf7;border-radius:12px\"><h2 style=\"color:#ff4757;margin-top:0\">"
                + "CrisisConnect</h2><p style=\"font-size:15px;line-height:1.6\">"
                + esc(text).replace("\n", "<br>") + "</p></div>";
        String body = "{\"sender\":{\"email\":" + q(from) + ",\"name\":" + q(fromName) + "},"
                + "\"to\":[{\"email\":" + q(to) + (toName == null ? "" : ",\"name\":" + q(toName)) + "}],"
                + "\"subject\":" + q(subject) + ",\"htmlContent\":" + q(html) + ",\"textContent\":" + q(text) + "}";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("api-key", apiKey).header("content-type", "application/json").header("accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 == 2) return new WhatsAppGateway.Result(true, "accepted");
            String detail = "Brevo " + res.statusCode() + ": " + res.body()
                    + (res.body() != null && res.body().contains("unrecognised IP")
                    ? " (Brevo -> Settings -> Security -> Authorized IPs -> deactivate blocking)" : "");
            LOG.warning("Brevo rejected e-mail to " + to + ": " + detail);
            return new WhatsAppGateway.Result(false, detail);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.log(Level.WARNING, "E-mail to " + to + " failed", e);
            return new WhatsAppGateway.Result(false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        return sb.append('"').toString();
    }
}
