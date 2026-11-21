package com.crisisconnect.util;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * Public SOS tracking IDs such as {@code CC-7KQ4MZ}.
 * The alphabet leaves out look-alike characters (0/O, 1/I/L) so codes are easy to read out over a phone.
 */
public final class TrackingCodes {

    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final SecureRandom RNG = new SecureRandom();
    private static final Pattern FORMAT = Pattern.compile("^CC-[" + ALPHABET + "]{6}$");

    private TrackingCodes() { }

    public static String next() {
        StringBuilder sb = new StringBuilder("CC-");
        for (int i = 0; i < 6; i++) sb.append(ALPHABET.charAt(RNG.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    /** Upper-cases, trims and adds the CC- prefix if the user typed only the 6 characters. */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toUpperCase().replace(" ", "");
        if (!s.startsWith("CC-") && s.length() == 6) s = "CC-" + s;
        return s;
    }

    public static boolean isValid(String code) {
        return code != null && FORMAT.matcher(code).matches();
    }
}
