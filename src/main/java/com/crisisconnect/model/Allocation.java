package com.crisisconnect.model;

/** A record that X units of a resource were dispatched to a request. */
public class Allocation extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private int requestId;
    private int resourceId;
    private int quantity;
    private double distanceKm;

    // denormalised fields filled by a JOIN query - handy for reports and tables
    private String requestTitle;
    private String resourceName;
    private String organizationName;
    private String city;

    public Allocation() { }

    public Allocation(int requestId, int resourceId, int quantity, double distanceKm) {
        this.requestId = requestId;
        this.resourceId = resourceId;
        this.quantity = quantity;
        this.distanceKm = distanceKm;
    }

    /** Rough ETA assuming 40 km/h average speed in disaster conditions. */
    public int etaMinutes() {
        return (int) Math.ceil(distanceKm / 40.0 * 60);
    }

    @Override
    public String displayName() {
        return quantity + " x " + resourceName + " -> " + requestTitle;
    }

    public int getRequestId() { return requestId; }
    public void setRequestId(int requestId) { this.requestId = requestId; }
    public int getResourceId() { return resourceId; }
    public void setResourceId(int resourceId) { this.resourceId = resourceId; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public double getDistanceKm() { return distanceKm; }
    public void setDistanceKm(double distanceKm) { this.distanceKm = distanceKm; }
    public String getRequestTitle() { return requestTitle; }
    public void setRequestTitle(String requestTitle) { this.requestTitle = requestTitle; }
    public String getResourceName() { return resourceName; }
    public void setResourceName(String resourceName) { this.resourceName = resourceName; }
    public String getOrganizationName() { return organizationName; }
    public void setOrganizationName(String organizationName) { this.organizationName = organizationName; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
}
