package com.crisisconnect.api;

import com.crisisconnect.model.Allocation;
import com.crisisconnect.model.EmergencyRequest;
import com.crisisconnect.model.Organization;
import com.crisisconnect.model.Resource;
import com.crisisconnect.service.DashboardStats;
import com.crisisconnect.service.MatchReport;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Tiny hand-written JSON serializer (no external library needed).
 * Converts Maps / Collections / primitives into JSON text.
 */
public final class Json {

    private Json() { }

    public static String stringify(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object v) {
        if (v == null) sb.append("null");
        else if (v instanceof Number || v instanceof Boolean) sb.append(v);
        else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            Iterator<? extends Map.Entry<?, ?>> it = m.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<?, ?> e = it.next();
                quote(sb, e.getKey() instanceof Enum<?> en ? en.name() : String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
                if (it.hasNext()) sb.append(',');
            }
            sb.append('}');
        } else if (v instanceof Collection<?> c) {
            sb.append('[');
            Iterator<?> it = c.iterator();
            while (it.hasNext()) {
                write(sb, it.next());
                if (it.hasNext()) sb.append(',');
            }
            sb.append(']');
        } else if (v instanceof Enum<?> en) quote(sb, en.name());
        else quote(sb, v.toString());
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
                }
            }
        }
        sb.append('"');
    }

    /** Generic list mapper: List<T> -> List<Map>. */
    public static <T> List<Map<String, Object>> list(Collection<T> items, Function<T, Map<String, Object>> fn) {
        return items.stream().map(fn).toList();
    }

    // ---------------- entity -> map converters ----------------
    public static Map<String, Object> of(EmergencyRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("title", r.getTitle());
        m.put("disasterType", r.getDisasterType().name());
        m.put("disasterLabel", r.getDisasterType().getLabel());
        m.put("resourceType", r.getResourceType().name());
        m.put("unit", r.getResourceType().getUnit());
        m.put("quantityNeeded", r.getQuantityNeeded());
        m.put("quantityFulfilled", r.getQuantityFulfilled());
        m.put("peopleAffected", r.getPeopleAffected());
        m.put("urgency", r.getUrgency().name());
        m.put("priority", r.priorityScore());
        m.put("city", r.getCity());
        m.put("lat", r.getLocation().getLatitude());
        m.put("lon", r.getLocation().getLongitude());
        m.put("status", r.getStatus().name());
        m.put("vulnerable", r.isVulnerableGroups());
        m.put("source", r.getSource());
        m.put("createdAt", r.getCreatedAt().toString());
        return m;
    }

    public static Map<String, Object> of(Resource r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("name", r.getName());
        m.put("type", r.getType().name());
        m.put("unit", r.getUnit());
        m.put("available", r.getAvailableQuantity());
        m.put("capacity", r.getTotalCapacity());
        m.put("organization", r.getOrganizationName());
        m.put("city", r.getCity());
        m.put("lat", r.getLocation().getLatitude());
        m.put("lon", r.getLocation().getLongitude());
        m.put("expiry", r.getExpiryDate().map(Object::toString).orElse(null));
        return m;
    }

    public static Map<String, Object> of(Organization o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.getId());
        m.put("name", o.getName());
        m.put("type", o.getType().name());
        m.put("city", o.getCity());
        m.put("phone", o.getContactPhone());
        m.put("email", o.getEmail());
        return m;
    }

    public static Map<String, Object> of(Allocation a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("requestId", a.getRequestId());
        m.put("resourceId", a.getResourceId());
        m.put("request", a.getRequestTitle());
        m.put("resource", a.getResourceName());
        m.put("organization", a.getOrganizationName());
        m.put("quantity", a.getQuantity());
        m.put("distanceKm", a.getDistanceKm());
        m.put("etaMinutes", a.etaMinutes());
        m.put("city", a.getCity());
        m.put("createdAt", a.getCreatedAt().toString());
        return m;
    }

    public static Map<String, Object> of(DashboardStats s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalRequests", s.totalRequests());
        m.put("activeRequests", s.activeRequests());
        m.put("criticalActive", s.criticalActive());
        m.put("fulfilledRequests", s.fulfilledRequests());
        m.put("fulfilmentRate", Math.round(s.fulfilmentRate() * 10) / 10.0);
        m.put("resources", s.resourceCount());
        m.put("unitsAvailable", s.unitsAvailable());
        m.put("organizations", s.organizations());
        m.put("allocations", s.allocations());
        m.put("unitsDispatched", s.unitsDispatched());
        m.put("peopleAffected", s.peopleAffectedActive());
        m.put("supply", s.supplyByType());
        m.put("demand", s.demandByType());
        m.put("urgency", s.activeByUrgency());
        return m;
    }

    public static Map<String, Object> of(MatchReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("headline", r.headline());
        m.put("allocations", r.allocationsCreated());
        m.put("units", r.unitsDispatched());
        m.put("fulfilled", r.fullyFulfilled());
        m.put("partial", r.partiallyFulfilled());
        m.put("unmet", r.unmet());
        m.put("failed", r.failedTransactions());
        m.put("durationMs", r.durationMs());
        m.put("log", r.log());
        return m;
    }
}
