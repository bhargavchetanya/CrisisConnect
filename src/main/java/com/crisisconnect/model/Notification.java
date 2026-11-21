package com.crisisconnect.model;

/** One message in the outbox (an SMS, WhatsApp message or e-mail). */
public class Notification extends BaseEntity {

    private static final long serialVersionUID = 1L;

    public enum Status { SIMULATED, SENT, FAILED }

    private NotificationChannel channel;
    private String recipient;        // phone number or e-mail address
    private String recipientName;
    private String message;
    private String event;            // SOS_RECEIVED, CRITICAL_ALERT, DISPATCH_ORDER, HELP_ON_THE_WAY ...
    private Integer requestId;       // nullable
    private Status status = Status.SIMULATED;

    public Notification() { }

    public Notification(NotificationChannel channel, String recipient, String recipientName, String message,
                        String event, Integer requestId) {
        this.channel = channel;
        this.recipient = recipient;
        this.recipientName = recipientName;
        this.message = message;
        this.event = event;
        this.requestId = requestId;
    }

    @Override
    public String displayName() { return channel + " -> " + recipient + ": " + message; }

    public NotificationChannel getChannel() { return channel; }
    public void setChannel(NotificationChannel channel) { this.channel = channel; }
    public String getRecipient() { return recipient; }
    public void setRecipient(String recipient) { this.recipient = recipient; }
    public String getRecipientName() { return recipientName; }
    public void setRecipientName(String recipientName) { this.recipientName = recipientName; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }
    public Integer getRequestId() { return requestId; }
    public void setRequestId(Integer requestId) { this.requestId = requestId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
}
