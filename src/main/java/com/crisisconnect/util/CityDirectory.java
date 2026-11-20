package com.crisisconnect.util;

import com.crisisconnect.model.GeoLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Known cities with coordinates (so users pick a city instead of typing lat/long).
 * LinkedHashMap keeps insertion order for the ComboBoxes.
 */
public final class CityDirectory {

    private static final Map<String, GeoLocation> CITIES = new LinkedHashMap<>();

    static {
        add("Patiala", 30.3398, 76.3869);
        add("Chandigarh", 30.7333, 76.7794);
        add("Mohali", 30.7046, 76.7179);
        add("Rajpura", 30.4840, 76.5940);
        add("Ambala", 30.3782, 76.7767);
        add("Sangrur", 30.2458, 75.8421);
        add("Ludhiana", 30.9010, 75.8573);
        add("Jalandhar", 31.3260, 75.5762);
        add("Amritsar", 31.6340, 74.8723);
        add("Bathinda", 30.2110, 74.9455);
        add("Samana", 30.1500, 76.1900);
        add("Shimla", 31.1048, 77.1734);
        add("New Delhi", 28.6139, 77.2090);
    }

    private CityDirectory() { }

    private static void add(String city, double lat, double lon) {
        CITIES.put(city.toLowerCase(), new GeoLocation(city, lat, lon));
    }

    public static Optional<GeoLocation> find(String city) {
        if (city == null) return Optional.empty();
        return Optional.ofNullable(CITIES.get(city.trim().toLowerCase()));
    }

    public static GeoLocation require(String city) {
        return find(city).orElseThrow(() -> new IllegalArgumentException("Unknown city: " + city));
    }

    public static List<String> cityNames() {
        List<String> names = new ArrayList<>();
        for (GeoLocation g : CITIES.values()) names.add(g.getCity());
        return Collections.unmodifiableList(names);
    }
}
