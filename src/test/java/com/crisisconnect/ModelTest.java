package com.crisisconnect;

import com.crisisconnect.exception.InsufficientResourceException;
import com.crisisconnect.exception.ValidationException;
import com.crisisconnect.model.DisasterType;
import com.crisisconnect.model.EmergencyRequest;
import com.crisisconnect.model.RequestStatus;
import com.crisisconnect.model.Resource;
import com.crisisconnect.model.ResourceFactory;
import com.crisisconnect.model.ResourceType;
import com.crisisconnect.model.ShelterResource;
import com.crisisconnect.model.UrgencyLevel;
import com.crisisconnect.util.CityDirectory;
import com.crisisconnect.util.GeoUtils;
import com.crisisconnect.util.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelTest {

    @Test
    void haversineDistancePatialaToChandigarh() {
        double d = GeoUtils.haversineKm(30.3398, 76.3869, 30.7333, 76.7794);
        assertTrue(d > 55 && d < 60, "expected ~57 km but was " + d);
    }

    @Test
    void allocateMoreThanAvailableThrowsCustomException() {
        Resource r = ResourceFactory.create(ResourceType.MEDICAL, 1, "Kits", 5, CityDirectory.require("Patiala"));
        InsufficientResourceException ex = assertThrows(InsufficientResourceException.class, () -> r.allocate(10));
        assertEquals(5, ex.getAvailable());
    }

    @Test
    void factoryCreatesCorrectSubclass() {
        assertInstanceOf(ShelterResource.class, ResourceFactory.create(ResourceType.SHELTER));
    }

    @Test
    void fulfilmentUpdatesStatus() {
        EmergencyRequest r = new EmergencyRequest("t", DisasterType.FIRE, ResourceType.FOOD, 10, 5,
                UrgencyLevel.HIGH, CityDirectory.require("Patiala"));
        r.addFulfilment(4);
        assertEquals(RequestStatus.PARTIALLY_FULFILLED, r.getStatus());
        r.addFulfilment(6);
        assertEquals(RequestStatus.FULFILLED, r.getStatus());
    }

    @Test
    void vulnerableGroupsRaisePriority() {
        EmergencyRequest a = new EmergencyRequest("a", DisasterType.FLOOD, ResourceType.FOOD, 1, 100,
                UrgencyLevel.MEDIUM, CityDirectory.require("Patiala"));
        EmergencyRequest b = new EmergencyRequest("b", DisasterType.FLOOD, ResourceType.FOOD, 1, 100,
                UrgencyLevel.MEDIUM, CityDirectory.require("Patiala"));
        b.setVulnerableGroups(true);
        assertTrue(b.priorityScore() > a.priorityScore());
        assertTrue(b.compareTo(a) < 0, "b must come first in the PriorityQueue");
    }

    @Test
    void validatorCollectsAllErrors() {
        ValidationException ex = assertThrows(ValidationException.class, () ->
                Validator.start().notBlank("", "Name").positive(0, "Qty").email("bad", "Email").validate());
        assertEquals(3, ex.getErrors().size());
    }
}
