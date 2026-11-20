package com.crisisconnect.service;

import com.crisisconnect.model.EmergencyRequest;
import com.crisisconnect.model.Resource;
import com.crisisconnect.model.ResourceType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.logging.Logger;

/**
 * The "intelligent" part of CrisisConnect - a greedy, priority-driven matcher.
 *
 * Algorithm:
 *  1. Put every active request into a PriorityQueue (highest priorityScore first).
 *  2. Poll the most urgent request.
 *  3. Collect candidate resources of the same type that still have stock (HashMap ledger)
 *     and are within the max radius.
 *  4. Sort candidates with a Comparator: lowest matchCost (polymorphic, distance based) first,
 *     then the biggest stock first.
 *  5. Take units greedily until the request is satisfied or candidates run out.
 *
 * This class is PURE (no database) - which makes it easy to unit-test.
 * Complexity: O(R log R + R * K log K) for R requests and K resources per type.
 */
public class MatchingEngine {

    private static final Logger LOG = Logger.getLogger(MatchingEngine.class.getName());

    private final double maxRadiusKm;

    public MatchingEngine(double maxRadiusKm) {
        this.maxRadiusKm = maxRadiusKm;
    }

    public MatchResult plan(Collection<EmergencyRequest> requests, Collection<Resource> resources) {
        // 1. priority queue of requests (uses EmergencyRequest.compareTo)
        PriorityQueue<EmergencyRequest> queue = new PriorityQueue<>();
        for (EmergencyRequest r : requests) {
            if (r.getStatus().isActive() && r.remainingQuantity() > 0) queue.offer(r);
        }

        // ledger of remaining stock: resourceId -> units (we never mutate the real objects here)
        Map<Integer, Integer> ledger = new HashMap<>();
        // resources grouped by type - EnumMap is the fastest Map for enum keys
        Map<ResourceType, List<Resource>> byType = new EnumMap<>(ResourceType.class);
        for (Resource res : resources) {
            if (res.getAvailableQuantity() <= 0) continue;
            ledger.put(res.getId(), res.getAvailableQuantity());
            byType.computeIfAbsent(res.getType(), t -> new ArrayList<>()).add(res);
        }

        List<AllocationPlan> plans = new ArrayList<>();
        List<EmergencyRequest> order = new ArrayList<>();
        Map<Integer, Integer> shortfalls = new LinkedHashMap<>();

        while (!queue.isEmpty()) {
            EmergencyRequest req = queue.poll();         // 2. most urgent first
            order.add(req);
            int remaining = req.remainingQuantity();

            // 3. candidates
            List<Resource> candidates = new ArrayList<>(byType.getOrDefault(req.getResourceType(), List.of()));
            candidates.removeIf(res -> ledger.getOrDefault(res.getId(), 0) <= 0
                    || res.getLocation().distanceTo(req.getLocation()) > maxRadiusKm);

            // 4. best candidates first
            Comparator<Resource> byCost = Comparator.comparingDouble(res -> res.matchCost(req.getLocation()));
            candidates.sort(byCost.thenComparing(res -> ledger.get(res.getId()), Comparator.reverseOrder()));

            // 5. greedy allocation
            for (Resource res : candidates) {
                if (remaining == 0) break;
                int take = Math.min(remaining, ledger.get(res.getId()));
                ledger.merge(res.getId(), -take, Integer::sum);
                plans.add(new AllocationPlan(req, res, take, res.getLocation().distanceTo(req.getLocation())));
                remaining -= take;
            }
            if (remaining > 0) shortfalls.put(req.getId(), remaining);
        }
        LOG.info(() -> String.format("Matching planned %d dispatches for %d requests (%d with shortfall)",
                plans.size(), order.size(), shortfalls.size()));
        return new MatchResult(plans, order, shortfalls);
    }

    public double getMaxRadiusKm() { return maxRadiusKm; }
}
