package com.crisisconnect.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Phone number helpers - everything is compared in E.164 form (+919876543210). */
public final class Phones {

    private Phones() { }

    /**
     * Converts the usual Indian ways of writing a mobile number to E.164:
     * 9876543210, 09876543210, 919876543210, +91 98765-43210  ->  +919876543210.
     * Numbers that already start with + and another country code are kept.
     * @return the E.164 number, or null if it does not look like a phone number
     */
    public static String toE164(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        boolean plus = t.startsWith("+");
        String d = t.replaceAll("[^0-9]", "");
        if (d.isEmpty()) return null;
        if (plus) return d.length() >= 8 && d.length() <= 15 ? "+" + d : null;
        if (d.length() == 11 && d.startsWith("0")) d = d.substring(1);
        if (d.length() == 10) return "+91" + d;
        if (d.length() == 12 && d.startsWith("91")) return "+" + d;
        return d.length() >= 8 && d.length() <= 15 ? "+" + d : null;
    }

    /** "7404267977, 6284974761" -> [+917404267977, +916284974761] (invalid entries skipped, duplicates removed). */
    public static List<String> parseList(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv != null) {
            for (String part : csv.split("[,;\\s]+")) {
                String e = toE164(part);
                if (e != null) out.add(e);
            }
        }
        return new ArrayList<>(out);
    }

    public static boolean same(String a, String b) {
        String x = toE164(a), y = toE164(b);
        return x != null && x.equals(y);
    }
}
