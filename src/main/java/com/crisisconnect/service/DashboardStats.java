package com.crisisconnect.service;

import com.crisisconnect.model.ResourceType;
import com.crisisconnect.model.UrgencyLevel;

import java.util.Map;

/** Read-only snapshot of numbers shown on the dashboard / website. */
public record DashboardStats(int totalRequests,
                             int activeRequests,
                             int criticalActive,
                             int fulfilledRequests,
                             int resourceCount,
                             int unitsAvailable,
                             int organizations,
                             int allocations,
                             int peopleAffectedActive,
                             int unitsDispatched,
                             Map<ResourceType, Integer> supplyByType,
                             Map<ResourceType, Integer> demandByType,
                             Map<UrgencyLevel, Integer> activeByUrgency) {

    /** Fulfilment rate in percent. */
    public double fulfilmentRate() {
        return totalRequests == 0 ? 0 : (fulfilledRequests * 100.0) / totalRequests;
    }
}
