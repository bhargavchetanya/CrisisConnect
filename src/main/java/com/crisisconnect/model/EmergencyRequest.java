package com.crisisconnect.model;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * An emergency request raised by an affected area.
 * Implements Comparable so that the natural order = highest priority first.
 */
public class EmergencyRequest extends BaseEntity implements Comparable<EmergencyRequest> {

    private static final long serialVersionUID = 1L;

    private String title;
    private DisasterType disasterType;
    private ResourceType resourceType;
    private int quantityNeeded;
    private int quantityFulfilled;
    private int peopleAffected;
    private UrgencyLevel urgency;
    private GeoLocation location;
    private RequestStatus status = RequestStatus.OPEN;
    private String contactPhone;
    private boolean vulnerableGroups;   // children / elderly / pregnant women present
    private String source = "DESKTOP";  // DESKTOP, WEB, CSV
    private String trackingCode;        // public tracking ID, e.g. CC-7KQ4MZ (set when saved)

    public EmergencyRequest() { }

    public EmergencyRequest(String title, DisasterType disasterType, ResourceType resourceType,
                            int quantityNeeded, int peopleAffected, UrgencyLevel urgency,
                            GeoLocation location) {
        this.title = title;
        this.disasterType = disasterType;
        this.resourceType = resourceType;
        this.quantityNeeded = quantityNeeded;
        this.peopleAffected = peopleAffected;
        this.urgency = urgency;
        this.location = location;
    }

    /**
     * Priority score - the heart of the triage logic.
     * <pre>
     *  score = urgencyWeight
     *        + 10 * log10(peopleAffected + 1)      (scale of impact, diminishing returns)
     *        + min(1.5 * hoursWaiting, 30)          (ageing - no request starves forever)
     *        + 15 if vulnerable groups present
     * </pre>
     */
    public double priorityScore() {
        double score = urgency.getWeight();
        score += 10 * Math.log10(peopleAffected + 1);
        score += Math.min(1.5 * hoursWaiting(), 30);
        if (vulnerableGroups) score += 15;
        return Math.round(score * 10) / 10.0;
    }

    public long hoursWaiting() {
        return Math.max(0, Duration.between(createdAt, LocalDateTime.now()).toHours());
    }

    public int remainingQuantity() {
        return Math.max(0, quantityNeeded - quantityFulfilled);
    }

    public double fulfilmentPercent() {
        return quantityNeeded == 0 ? 100 : (quantityFulfilled * 100.0) / quantityNeeded;
    }

    /** Records a delivery and updates status automatically. */
    public void addFulfilment(int qty) {
        quantityFulfilled = Math.min(quantityNeeded, quantityFulfilled + qty);
        status = remainingQuantity() == 0 ? RequestStatus.FULFILLED : RequestStatus.PARTIALLY_FULFILLED;
    }

    /** Natural ordering: higher priority first, older request first on a tie. */
    @Override
    public int compareTo(EmergencyRequest other) {
        int cmp = Double.compare(other.priorityScore(), this.priorityScore());
        return cmp != 0 ? cmp : this.createdAt.compareTo(other.createdAt);
    }

    @Override
    public String displayName() {
        return String.format("[%s] %s - %d %s @ %s", urgency, title, remainingQuantity(),
                resourceType.getUnit(), getCity());
    }

    // ---------- getters / setters ----------
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public DisasterType getDisasterType() { return disasterType; }
    public void setDisasterType(DisasterType disasterType) { this.disasterType = disasterType; }
    public ResourceType getResourceType() { return resourceType; }
    public void setResourceType(ResourceType resourceType) { this.resourceType = resourceType; }
    public int getQuantityNeeded() { return quantityNeeded; }
    public void setQuantityNeeded(int quantityNeeded) { this.quantityNeeded = quantityNeeded; }
    public int getQuantityFulfilled() { return quantityFulfilled; }
    public void setQuantityFulfilled(int quantityFulfilled) { this.quantityFulfilled = quantityFulfilled; }
    public int getPeopleAffected() { return peopleAffected; }
    public void setPeopleAffected(int peopleAffected) { this.peopleAffected = peopleAffected; }
    public UrgencyLevel getUrgency() { return urgency; }
    public void setUrgency(UrgencyLevel urgency) { this.urgency = urgency; }
    public GeoLocation getLocation() { return location; }
    public void setLocation(GeoLocation location) { this.location = location; }
    public RequestStatus getStatus() { return status; }
    public void setStatus(RequestStatus status) { this.status = status; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public boolean isVulnerableGroups() { return vulnerableGroups; }
    public void setVulnerableGroups(boolean vulnerableGroups) { this.vulnerableGroups = vulnerableGroups; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getCity() { return location == null ? "" : location.getCity(); }
    public String getTrackingCode() { return trackingCode; }
    public void setTrackingCode(String trackingCode) { this.trackingCode = trackingCode; }
}
