package com.crisisconnect.model;

import com.crisisconnect.util.GeoUtils;

import java.io.Serializable;
import java.util.Objects;

/**
 * Immutable value object for a place on the map.
 * final class + private final fields = immutability.
 */
public final class GeoLocation implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String city;
    private final double latitude;
    private final double longitude;

    public GeoLocation(String city, double latitude, double longitude) {
        this.city = Objects.requireNonNull(city, "city must not be null");
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Invalid coordinates: " + latitude + ", " + longitude);
        }
        this.latitude = latitude;
        this.longitude = longitude;
    }

    /** Great-circle distance in kilometres (Haversine formula). */
    public double distanceTo(GeoLocation other) {
        return GeoUtils.haversineKm(latitude, longitude, other.latitude, other.longitude);
    }

    public String getCity() { return city; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GeoLocation g)) return false;
        return Double.compare(latitude, g.latitude) == 0
                && Double.compare(longitude, g.longitude) == 0
                && city.equals(g.city);
    }

    @Override
    public int hashCode() { return Objects.hash(city, latitude, longitude); }

    @Override
    public String toString() { return String.format("%s (%.4f, %.4f)", city, latitude, longitude); }
}
