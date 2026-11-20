package com.crisisconnect.service;

import com.crisisconnect.model.EmergencyRequest;
import com.crisisconnect.model.Resource;

/** One proposed dispatch produced by the matching engine (not yet saved). */
public record AllocationPlan(EmergencyRequest request, Resource resource, int quantity, double distanceKm) { }
