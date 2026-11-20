package com.crisisconnect.service;

import com.crisisconnect.model.Allocation;
import com.crisisconnect.model.EmergencyRequest;
import com.crisisconnect.model.Organization;
import com.crisisconnect.model.Resource;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Whole-database snapshot that is written to disk with Object Serialization. */
public class DataSnapshot implements Serializable {

    private static final long serialVersionUID = 20261014L;

    private final LocalDateTime takenAt = LocalDateTime.now();
    private final ArrayList<Organization> organizations;
    private final ArrayList<Resource> resources;
    private final ArrayList<EmergencyRequest> requests;
    private final ArrayList<Allocation> allocations;
    private transient String loadedFrom;   // transient -> NOT serialized

    public DataSnapshot(List<Organization> o, List<Resource> r, List<EmergencyRequest> q, List<Allocation> a) {
        this.organizations = new ArrayList<>(o);
        this.resources = new ArrayList<>(r);
        this.requests = new ArrayList<>(q);
        this.allocations = new ArrayList<>(a);
    }

    public LocalDateTime getTakenAt() { return takenAt; }
    public List<Organization> getOrganizations() { return organizations; }
    public List<Resource> getResources() { return resources; }
    public List<EmergencyRequest> getRequests() { return requests; }
    public List<Allocation> getAllocations() { return allocations; }
    public String getLoadedFrom() { return loadedFrom; }
    void setLoadedFrom(String loadedFrom) { this.loadedFrom = loadedFrom; }

    public String summary() {
        return String.format("%d organizations, %d resources, %d requests, %d dispatches",
                organizations.size(), resources.size(), requests.size(), allocations.size());
    }
}
