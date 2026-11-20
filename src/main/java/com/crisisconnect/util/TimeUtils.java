package com.crisisconnect.util;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Date/time helpers on java.time (LocalDate / LocalDateTime). */
public final class TimeUtils {

    public static final DateTimeFormatter DB_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    public static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");
    public static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private TimeUtils() { }

    public static String display(LocalDateTime t) { return t == null ? "-" : t.format(DISPLAY); }

    /** "5 min ago", "3 h ago", "2 d ago" */
    public static String ago(LocalDateTime t) {
        if (t == null) return "-";
        Duration d = Duration.between(t, LocalDateTime.now());
        if (d.toMinutes() < 1) return "just now";
        if (d.toMinutes() < 60) return d.toMinutes() + " min ago";
        if (d.toHours() < 24) return d.toHours() + " h ago";
        return d.toDays() + " d ago";
    }
}
