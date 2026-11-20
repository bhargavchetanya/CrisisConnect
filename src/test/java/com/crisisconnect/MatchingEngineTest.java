package com.crisisconnect;

import com.crisisconnect.model.DisasterType;
import com.crisisconnect.model.EmergencyRequest;
import com.crisisconnect.model.Resource;
import com.crisisconnect.model.ResourceFactory;
import com.crisisconnect.model.ResourceType;
import com.crisisconnect.model.UrgencyLevel;
import com.crisisconnect.service.MatchResult;
import com.crisisconnect.service.MatchingEngine;
import com.crisisconnect.util.CityDirectory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchingEngineTest {

    private static EmergencyRequest req(int id, UrgencyLevel u, int qty, String city) {
        EmergencyRequest r = new EmergencyRequest("R" + id, DisasterType.FLOOD, ResourceType.FOOD, qty, 100, u,
                CityDirectory.require(city));
        r.setId(id);
        return r;
    }

    private static Resource res(int id, int qty, String city) {
        Resource r = ResourceFactory.create(ResourceType.FOOD, 1, "Food" + id, qty, CityDirectory.require(city));
        r.setId(id);
        return r;
    }

    @Test
    void criticalRequestIsServedFirst() {
        EmergencyRequest low = req(1, UrgencyLevel.LOW, 50, "Patiala");
        EmergencyRequest critical = req(2, UrgencyLevel.CRITICAL, 50, "Patiala");
        MatchResult r = new MatchingEngine(200).plan(List.of(low, critical), List.of(res(1, 60, "Patiala")));

        assertEquals(critical, r.processingOrder().get(0));
        assertEquals(50, r.plans().get(0).quantity());           // critical fully served
        assertEquals(40, r.shortfalls().get(1));                 // low gets only the remaining 10 of 50
    }

    @Test
    void nearestResourceIsPreferred() {
        EmergencyRequest q = req(1, UrgencyLevel.HIGH, 10, "Patiala");
        Resource far = res(1, 100, "Amritsar");
        Resource near = res(2, 100, "Rajpura");
        MatchResult r = new MatchingEngine(400).plan(List.of(q), List.of(far, near));
        assertEquals(near, r.plans().get(0).resource());
    }

    @Test
    void resourcesOutsideRadiusAreIgnored() {
        EmergencyRequest q = req(1, UrgencyLevel.HIGH, 10, "Patiala");
        MatchResult r = new MatchingEngine(50).plan(List.of(q), List.of(res(1, 100, "New Delhi")));
        assertTrue(r.plans().isEmpty());
        assertEquals(10, r.shortfalls().get(1));
    }

    @Test
    void requestIsSplitAcrossResources() {
        EmergencyRequest q = req(1, UrgencyLevel.HIGH, 100, "Patiala");
        MatchResult r = new MatchingEngine(300).plan(List.of(q), List.of(res(1, 40, "Patiala"), res(2, 70, "Rajpura")));
        assertEquals(2, r.plans().size());
        assertEquals(100, r.plans().stream().mapToInt(p -> p.quantity()).sum());
        assertTrue(r.shortfalls().isEmpty());
    }
}
