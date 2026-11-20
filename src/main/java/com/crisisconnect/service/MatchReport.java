package com.crisisconnect.service;

import java.util.List;

/** Summary of a matching run after it has been committed to the database. */
public record MatchReport(int requestsProcessed,
                          int allocationsCreated,
                          int unitsDispatched,
                          int fullyFulfilled,
                          int partiallyFulfilled,
                          int unmet,
                          int failedTransactions,
                          long durationMs,
                          List<String> log) {

    public String headline() {
        return String.format("%d dispatches | %d units | %d fulfilled | %d partial | %d unmet",
                allocationsCreated, unitsDispatched, fullyFulfilled, partiallyFulfilled, unmet);
    }
}
