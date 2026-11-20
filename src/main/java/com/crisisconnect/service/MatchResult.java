package com.crisisconnect.service;

import com.crisisconnect.model.EmergencyRequest;

import java.util.List;
import java.util.Map;

/**
 * Output of the matching engine.
 * @param plans           every proposed dispatch
 * @param processingOrder requests in the order the PriorityQueue served them
 * @param shortfalls      requestId -> units that could NOT be covered
 */
public record MatchResult(List<AllocationPlan> plans,
                          List<EmergencyRequest> processingOrder,
                          Map<Integer, Integer> shortfalls) { }
