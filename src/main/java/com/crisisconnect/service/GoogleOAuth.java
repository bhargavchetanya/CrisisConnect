package com.crisisconnect.service;

import com.crisisconnect.util.Security;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Continue with Google" - OAuth 2.0 authorization-code flow with PKCE (same design as Partner in Project).
 * <ol>
 *   <li>/api/auth/google redirects to Google with a random state and a PKCE code challenge</li>
 *   <li>Google redirects back to /api/auth/google/callback?code=...&state=...</li>
 *   <li>the server exchanges the code (+ code verifier + client secret) for an access token</li>
 *   <li>and reads the verified e-mail and name from Google's OpenID Connect userinfo endpoint</li>
 * </ol>
 * Configured with GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET.
 */
public class GoogleOAuth {

    /** What we need from Google: who the person is and whether Google verified their e-mail. */
    public record Profile(String sub, String email, boolean emailVerified, String name) { }

    private final String clientId;
    private final String clientSecret;
    private final String authUrl;
    private final String tokenUrl;
    private final String userInfoUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public GoogleOAuth(String clientId, String clientSecret) {
        this(clientId, clientSecret, "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token", "https://openidconnect.googleapis.com/v1/userinfo");
    }

    /** Endpoints are configurable only so tests can use a local stub server. */
    public GoogleOAuth(String clientId, String clientSecret, String authUrl, String tokenUrl, String userInfoUrl) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.authUrl = authUrl;
        this.tokenUrl = tokenUrl;
        this.userInfoUrl = userInfoUrl;
    }

    public boolean isConfigured() { return !clientId.isEmpty() && !clientSecret.isEmpty(); }

    public static String newVerifier() { return Security.randomToken() + Security.randomToken(); }

    public static String challenge(String verifier) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String authorizationUrl(String redirectUri, String state, String verifier) {
        return authUrl + "?client_id=" + enc(clientId) + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code&scope=" + enc("openid email profile") + "&state=" + enc(state)
                + "&code_challenge=" + enc(challenge(verifier)) + "&code_challenge_method=S256&prompt=select_account";
    }

    public Profile exchange(String code, String verifier, String redirectUri) throws IOException, InterruptedException {
        String form = "code=" + enc(code) + "&client_id=" + enc(clientId) + "&client_secret=" + enc(clientSecret)
                + "&redirect_uri=" + enc(redirectUri) + "&grant_type=authorization_code&code_verifier=" + enc(verifier);
        HttpResponse<String> tok = http.send(HttpRequest.newBuilder(URI.create(tokenUrl)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        if (tok.statusCode() / 100 != 2) throw new IOException("Google token exchange failed: HTTP " + tok.statusCode() + " " + tok.body());
        String accessToken = str(tok.body(), "access_token");
        if (accessToken == null) throw new IOException("Google returned no access token");
        HttpResponse<String> info = http.send(HttpRequest.newBuilder(URI.create(userInfoUrl)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + accessToken).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (info.statusCode() / 100 != 2) throw new IOException("Google userinfo failed: HTTP " + info.statusCode());
        String body = info.body();
        return new Profile(str(body, "sub"), str(body, "email"),
                Pattern.compile("\"email_verified\"\\s*:\\s*(true|\"true\")").matcher(body).find(), str(body, "name"));
    }

    /** Reads a string field from a flat JSON object (Google's token / userinfo answers). */
    static String str(String json, String field) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (!m.find()) return null;
        String v = m.group(1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length()) {
                char n = v.charAt(++i);
                if (n == 'u' && i + 4 < v.length()) { sb.append((char) Integer.parseInt(v.substring(i + 1, i + 5), 16)); i += 4; }
                else sb.append(switch (n) { case 'n' -> '\n'; case 't' -> '\t'; default -> n; });
            } else sb.append(c);
        }
        return sb.toString();
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
