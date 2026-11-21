package com.crisisconnect.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Password hashing and token helpers built only on the JDK (no external crypto library).
 * <ul>
 *   <li>Passwords: PBKDF2-HMAC-SHA256, 210 000 iterations, 16-byte random salt
 *       (OWASP recommendation). Stored as {@code pbkdf2$iterations$salt$hash}.</li>
 *   <li>Session tokens: 32 random bytes; only their SHA-256 is stored in the database,
 *       so a leaked database cannot be used to hijack sessions.</li>
 * </ul>
 */
public final class Security {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int ITERATIONS = 210_000;
    private static final int KEY_BITS = 256;

    private Security() { }

    public static String hashPassword(String password) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        byte[] hash = pbkdf2(password.toCharArray(), salt, ITERATIONS);
        Base64.Encoder b64 = Base64.getEncoder();
        return "pbkdf2$" + ITERATIONS + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
    }

    public static boolean verifyPassword(String password, String stored) {
        if (password == null || stored == null) return false;
        String[] p = stored.split("\\$");
        if (p.length != 4 || !"pbkdf2".equals(p[0])) return false;
        try {
            int iterations = Integer.parseInt(p[1]);
            byte[] salt = Base64.getDecoder().decode(p[2]);
            byte[] expected = Base64.getDecoder().decode(p[3]);
            byte[] actual = pbkdf2(password.toCharArray(), salt, iterations);
            return MessageDigest.isEqual(expected, actual);      // constant-time comparison
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 not available", e);
        }
    }

    /** URL-safe random token (session cookies). */
    public static String randomToken() {
        byte[] b = new byte[32];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Six-digit one-time code for e-mail verification. */
    public static String sixDigitCode() {
        return String.format("%06d", RNG.nextInt(1_000_000));
    }

    public static String sha256(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Random readable password, used when the admin password is not configured. */
    public static String randomPassword() {
        String chars = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 14; i++) sb.append(chars.charAt(RNG.nextInt(chars.length())));
        return sb.append("#7").toString();
    }

    /** Hides most of a phone number or e-mail for public pages: +91 98•••••210, b•••@gmail.com. */
    public static String mask(String contact) {
        if (contact == null || contact.isBlank()) return "";
        String c = contact.trim();
        int at = c.indexOf('@');
        if (at > 0) return c.charAt(0) + "•••" + c.substring(at);
        if (c.length() <= 5) return "•••";
        return c.substring(0, Math.min(6, c.length() - 3)) + "•••" + c.substring(c.length() - 3);
    }
}
