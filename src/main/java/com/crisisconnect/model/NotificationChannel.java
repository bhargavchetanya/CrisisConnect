package com.crisisconnect.model;

/** Channels the notification service can use. SMS and WhatsApp are simulated; e-mail can be real (Brevo). */
public enum NotificationChannel {
    SMS("SMS"), WHATSAPP("WhatsApp"), EMAIL("E-mail");

    private final String label;

    NotificationChannel(String label) { this.label = label; }

    public String getLabel() { return label; }
}
