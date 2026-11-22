package com.crisisconnect.service;

import com.crisisconnect.dao.AllocationDao;
import com.crisisconnect.dao.EmergencyRequestDao;
import com.crisisconnect.dao.OrganizationDao;
import com.crisisconnect.dao.ResourceDao;
import com.crisisconnect.db.TransactionManager;
import com.crisisconnect.event.CrisisEvent;
import com.crisisconnect.event.EventBus;
import com.crisisconnect.exception.AllocationFailedException;
import com.crisisconnect.exception.EntityNotFoundException;
import com.crisisconnect.exception.ValidationException;
import com.crisisconnect.model.Allocation;
import com.crisisconnect.model.EmergencyRequest;
import com.crisisconnect.model.Organization;
import com.crisisconnect.model.RequestStatus;
import com.crisisconnect.model.Resource;
import com.crisisconnect.model.ResourceType;
import com.crisisconnect.model.UrgencyLevel;
import com.crisisconnect.model.Verification;
import com.crisisconnect.util.CollectionUtils;
import com.crisisconnect.util.Validator;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.logging.Logger;

/**
 * Service layer / facade - the ONLY entry point used by the JavaFX UI and the REST API.
 * Business rules + validation live here; persistence is delegated to DAOs
 * (injected through the constructor so tests can pass their own).
 */
public class CrisisService {

    private static final Logger LOG = Logger.getLogger(CrisisService.class.getName());

    private final OrganizationDao orgDao;
    private final ResourceDao resourceDao;
    private final EmergencyRequestDao requestDao;
    private final AllocationDao allocationDao;
    private final MatchingEngine engine;
    private final EventBus events = EventBus.get();
    /** Typed observers (e.g. the SMS/WhatsApp NotificationService). CopyOnWrite: API + UI threads. */
    private final List<CrisisNotifier> notifiers = new java.util.concurrent.CopyOnWriteArrayList<>();

    public CrisisService(OrganizationDao orgDao, ResourceDao resourceDao, EmergencyRequestDao requestDao,
                         AllocationDao allocationDao, MatchingEngine engine) {
        this.orgDao = Objects.requireNonNull(orgDao);
        this.resourceDao = Objects.requireNonNull(resourceDao);
        this.requestDao = Objects.requireNonNull(requestDao);
        this.allocationDao = Objects.requireNonNull(allocationDao);
        this.engine = Objects.requireNonNull(engine);
    }

    public void addNotifier(CrisisNotifier n) { notifiers.add(Objects.requireNonNull(n)); }

    /** Runs an observer callback; a failing listener is logged but never breaks the business operation. */
    private void fire(java.util.function.Consumer<CrisisNotifier> call) {
        for (CrisisNotifier n : notifiers) {
            try { call.accept(n); }
            catch (RuntimeException e) { LOG.warning("Notifier failed: " + e); }
        }
    }

    // =====================================================================
    //  Organizations
    // =====================================================================
    public Organization registerOrganization(Organization o) throws ValidationException {
        return registerOrganization(o, true);
    }

    /** @param notify false when the organization comes from a sign-up (the sign-up alert already covers it) */
    public Organization registerOrganization(Organization o, boolean notify) throws ValidationException {
        Validator.start()
                .notBlank(o.getName(), "Organization name")
                .notNull(o.getType(), "Organization type")
                .notNull(o.getLocation(), "City")
                .phone(o.getContactPhone(), "Phone")
                .email(o.getEmail(), "E-mail")
                .validate();
        if (orgDao.findByName(o.getName().trim()).isPresent()) {
            throw new ValidationException("An organization named '" + o.getName() + "' already exists");
        }
        o.setName(o.getName().trim());
        orgDao.save(o);
        events.publish(new CrisisEvent(CrisisEvent.Type.ORGANIZATION_ADDED, o.getName() + " joined the network"));
        if (notify) fire(n -> n.organizationRegistered(o));
        return o;
    }

    public List<Organization> organizations() { return orgDao.findAll(); }

    public java.util.Optional<Organization> organization(int id) { return orgDao.findById(id); }

    /** Deletes an organization; its resources are removed by ON DELETE CASCADE. */
    public void deleteOrganization(int id) throws EntityNotFoundException, ValidationException {
        Organization o = orgDao.findById(id).orElseThrow(() -> new EntityNotFoundException("Organization", id));
        boolean hasDispatches = allocationDao.findAll().stream()
                .anyMatch(a -> resourceDao.findById(a.getResourceId()).map(r -> r.getOrganizationId() == id).orElse(false));
        if (hasDispatches) {
            throw new ValidationException("'" + o.getName() + "' has dispatch history and cannot be deleted "
                    + "(it would erase the record of who sent what). Remove its resources instead.");
        }
        orgDao.delete(id);
        events.publish(new CrisisEvent(CrisisEvent.Type.ORGANIZATION_ADDED, o.getName() + " removed from the network"));
    }

    // =====================================================================
    //  Resources
    // =====================================================================
    public Resource addResource(Resource r) throws ValidationException {
        Validator.start()
                .notBlank(r.getName(), "Resource name")
                .positive(r.getTotalCapacity(), "Quantity")
                .max(r.getTotalCapacity(), 1_000_000, "Quantity")
                .notNull(r.getLocation(), "City")
                .validate();
        if (orgDao.findById(r.getOrganizationId()).isEmpty()) {
            throw new ValidationException("Please select a valid organization");
        }
        resourceDao.save(r);
        events.publish(new CrisisEvent(CrisisEvent.Type.RESOURCE_ADDED,
                r.getTotalCapacity() + " " + r.getUnit() + " of " + r.getName() + " added in " + r.getCity()));
        return r;
    }

    public void deleteResource(int id) throws EntityNotFoundException {
        Resource r = resourceDao.findById(id).orElseThrow(() -> new EntityNotFoundException("Resource", id));
        resourceDao.delete(id);
        events.publish(new CrisisEvent(CrisisEvent.Type.RESOURCE_REMOVED, r.getName() + " removed"));
    }

    /** Adds stock back to a resource (e.g. new donation arrived). */
    public void restock(int id, int extra) throws EntityNotFoundException, ValidationException {
        Validator.start().positive(extra, "Restock quantity").validate();
        Resource r = resourceDao.findById(id).orElseThrow(() -> new EntityNotFoundException("Resource", id));
        r.setAvailableQuantity(r.getAvailableQuantity() + extra);
        r.setTotalCapacity(r.getTotalCapacity() + extra);
        resourceDao.update(r);
        events.publish(new CrisisEvent(CrisisEvent.Type.RESOURCE_ADDED, "+" + extra + " " + r.getUnit() + " restocked: " + r.getName()));
    }

    public List<Resource> resources() { return resourceDao.findAll(); }

    public java.util.Optional<Resource> resource(int id) { return resourceDao.findById(id); }

    // =====================================================================
    //  Emergency requests
    // =====================================================================
    public EmergencyRequest createRequest(EmergencyRequest r) throws ValidationException {
        Validator.start()
                .notBlank(r.getTitle(), "Title")
                .notNull(r.getDisasterType(), "Disaster type")
                .notNull(r.getResourceType(), "Resource needed")
                .notNull(r.getUrgency(), "Urgency")
                .notNull(r.getLocation(), "City")
                .positive(r.getQuantityNeeded(), "Quantity needed")
                .max(r.getQuantityNeeded(), 100_000, "Quantity needed")
                .positive(r.getPeopleAffected(), "People affected")
                .phone(r.getContactPhone(), "Contact phone")
                .validate();
        r.setTitle(r.getTitle().trim());
        r.setStatus(RequestStatus.OPEN);
        requestDao.save(r);
        events.publish(new CrisisEvent(CrisisEvent.Type.REQUEST_CREATED,
                "[" + r.getUrgency() + "] " + r.getTitle() + " - " + r.getCity() + " (via " + r.getSource() + ")"));
        fire(n -> n.requestCreated(r));
        return r;
    }

    public void cancelRequest(int id) throws EntityNotFoundException, ValidationException {
        EmergencyRequest r = requestDao.findById(id).orElseThrow(() -> new EntityNotFoundException("Request", id));
        if (!r.getStatus().isActive()) {
            throw new ValidationException("Only open requests can be cancelled (current: " + r.getStatus() + ")");
        }
        requestDao.updateProgress(id, r.getQuantityFulfilled(), RequestStatus.CANCELLED);
        r.setStatus(RequestStatus.CANCELLED);
        events.publish(new CrisisEvent(CrisisEvent.Type.REQUEST_UPDATED, "Request cancelled: " + r.getTitle()));
        fire(n -> n.requestCancelled(r));
    }

    // =====================================================================
    //  SOS verification (government officers / coordinators)
    // =====================================================================
    /** Confirms that an SOS is genuine: from now on Smart Match may dispatch to it. */
    public EmergencyRequest verifyRequest(int id, String by, String note) throws EntityNotFoundException, ValidationException {
        EmergencyRequest r = requestDao.findById(id).orElseThrow(() -> new EntityNotFoundException("Request", id));
        if (r.getVerification() != Verification.UNVERIFIED) {
            throw new ValidationException("This SOS was already " + r.getVerification().getLabel().toLowerCase());
        }
        if (!r.getStatus().isActive()) throw new ValidationException("This SOS is no longer open");
        r.setVerification(Verification.VERIFIED);
        r.setVerifiedBy(by);
        r.setVerifiedAt(LocalDateTime.now());
        r.setVerificationNote(note == null || note.isBlank() ? null : note.trim());
        requestDao.updateVerification(id, r.getVerification(), by, r.getVerifiedAt(), r.getVerificationNote());
        events.publish(new CrisisEvent(CrisisEvent.Type.REQUEST_UPDATED, "SOS verified: " + r.getTitle() + " - " + r.getCity()));
        fire(n -> n.requestVerified(r));
        return r;
    }

    /** Rejects an SOS as not genuine: it is closed and never receives resources. */
    public EmergencyRequest rejectRequest(int id, String by, String reason) throws EntityNotFoundException, ValidationException {
        EmergencyRequest r = requestDao.findById(id).orElseThrow(() -> new EntityNotFoundException("Request", id));
        if (r.getVerification() != Verification.UNVERIFIED) {
            throw new ValidationException("This SOS was already " + r.getVerification().getLabel().toLowerCase());
        }
        Validator.start().notBlank(reason, "Reason").validate();
        r.setVerification(Verification.REJECTED);
        r.setVerifiedBy(by);
        r.setVerifiedAt(LocalDateTime.now());
        r.setVerificationNote(reason.trim());
        requestDao.updateVerification(id, r.getVerification(), by, r.getVerifiedAt(), r.getVerificationNote());
        requestDao.updateProgress(id, r.getQuantityFulfilled(), RequestStatus.CANCELLED);
        r.setStatus(RequestStatus.CANCELLED);
        events.publish(new CrisisEvent(CrisisEvent.Type.REQUEST_UPDATED, "SOS rejected as not genuine: " + r.getTitle()));
        fire(n -> n.requestRejected(r));
        return r;
    }

    /** Coordinator re-triage: raise or lower the urgency of an active request. */
    public EmergencyRequest changeUrgency(int id, UrgencyLevel urgency) throws EntityNotFoundException, ValidationException {
        EmergencyRequest r = requestDao.findById(id).orElseThrow(() -> new EntityNotFoundException("Request", id));
        if (!r.getStatus().isActive()) throw new ValidationException("Only active requests can be re-prioritised");
        Validator.start().notNull(urgency, "Urgency").validate();
        requestDao.updateUrgency(id, urgency);
        r.setUrgency(urgency);
        events.publish(new CrisisEvent(CrisisEvent.Type.REQUEST_UPDATED, "Urgency of '" + r.getTitle() + "' set to " + urgency));
        return r;
    }

    public java.util.Optional<EmergencyRequest> request(int id) { return requestDao.findById(id); }

    /** Public tracking lookup (CC-XXXXXX). */
    public java.util.Optional<EmergencyRequest> findByTrackingCode(String code) {
        return requestDao.findByTrackingCode(code);
    }

    public List<EmergencyRequest> requests() { return requestDao.findAll(); }

    /** Active requests ordered exactly as the PriorityQueue would serve them. */
    public List<EmergencyRequest> priorityQueueSnapshot() {
        PriorityQueue<EmergencyRequest> pq = new PriorityQueue<>(requestDao.findActive());
        List<EmergencyRequest> ordered = new ArrayList<>(pq.size());
        while (!pq.isEmpty()) ordered.add(pq.poll());
        return ordered;
    }

    public List<Allocation> allocations() { return allocationDao.findAll(); }

    public List<Allocation> allocationsFor(int requestId) { return allocationDao.findByRequest(requestId); }

    // =====================================================================
    //  Smart matching (uses PriorityQueue + transactions)
    // =====================================================================
    /** Preview only - nothing is written. */
    public MatchResult previewMatching() {
        return previewMatching(r -> true);
    }

    /** @param scope which requests to serve (e.g. only one coordinator's area); stock may come from anywhere in range */
    public MatchResult previewMatching(java.util.function.Predicate<EmergencyRequest> scope) {
        List<EmergencyRequest> verified = new ArrayList<>(requestDao.findActive());
        verified.removeIf(r -> !r.isVerified() || !scope.test(r));     // unverified SOS wait for an officer
        return engine.plan(verified, resourceDao.findAvailable());
    }

    /**
     * Runs the engine and commits the plan. Every request is dispatched inside its OWN transaction:
     * if anything fails for that request (e.g. stock changed meanwhile) its changes are rolled back
     * and the other requests are not affected.
     */
    public synchronized MatchReport runMatching() {
        return runMatching(r -> true);
    }

    /** Smart Match for the requests in scope only (a coordinator dispatches for his own area). */
    public synchronized MatchReport runMatching(java.util.function.Predicate<EmergencyRequest> scope) {
        long start = System.currentTimeMillis();
        MatchResult result = previewMatching(scope);
        List<String> log = new ArrayList<>();

        // group plans per request, keeping priority order
        Map<EmergencyRequest, List<AllocationPlan>> grouped = new LinkedHashMap<>();
        for (EmergencyRequest r : result.processingOrder()) grouped.put(r, new ArrayList<>());
        for (AllocationPlan p : result.plans()) grouped.get(p.request()).add(p);

        int allocations = 0, units = 0, full = 0, partial = 0, unmet = 0, failed = 0;
        int rank = 1;
        for (Map.Entry<EmergencyRequest, List<AllocationPlan>> e : grouped.entrySet()) {
            EmergencyRequest req = e.getKey();
            List<AllocationPlan> plans = e.getValue();
            String head = String.format("#%d  [%s | score %.1f] %s (%s)", rank++, req.getUrgency(),
                    req.priorityScore(), req.getTitle(), req.getCity());
            if (plans.isEmpty()) {
                unmet++;
                log.add(head + "\n      x no matching " + req.getResourceType().getLabel()
                        + " within " + (int) engine.getMaxRadiusKm() + " km");
                continue;
            }
            try {
                int dispatched = TransactionManager.inTransaction(() -> {
                    int sum = 0;
                    for (AllocationPlan p : plans) {
                        Resource fresh = resourceDao.findById(p.resource().getId())
                                .orElseThrow(() -> new EntityNotFoundException("Resource", p.resource().getId()));
                        fresh.allocate(p.quantity());                // may throw InsufficientResourceException
                        resourceDao.updateQuantity(fresh.getId(), fresh.getAvailableQuantity());
                        allocationDao.save(new Allocation(req.getId(), fresh.getId(), p.quantity(), p.distanceKm()));
                        req.addFulfilment(p.quantity());
                        sum += p.quantity();
                    }
                    requestDao.updateProgress(req.getId(), req.getQuantityFulfilled(), req.getStatus());
                    return sum;
                });
                allocations += plans.size();
                units += dispatched;
                fire(n -> n.resourcesDispatched(req, plans));
                if (req.getStatus() == RequestStatus.FULFILLED) full++; else partial++;
                StringBuilder sb = new StringBuilder(head);
                for (AllocationPlan p : plans) {
                    sb.append(String.format("%n      -> %d %s from %s, %s (%.1f km)", p.quantity(),
                            p.resource().getUnit(), p.resource().getName(), p.resource().getCity(), p.distanceKm()));
                }
                sb.append(req.getStatus() == RequestStatus.FULFILLED ? "\n      => FULFILLED"
                        : "\n      ~ PARTIAL - still short of " + req.remainingQuantity());
                log.add(sb.toString());
            } catch (AllocationFailedException ex) {
                failed++;
                log.add(head + "\n      ! ROLLED BACK: " + ex.getMessage());
            }
        }
        MatchReport report = new MatchReport(result.processingOrder().size(), allocations, units, full,
                partial, unmet, failed, System.currentTimeMillis() - start, log);
        LOG.info("Matching run finished: " + report.headline());
        events.publish(new CrisisEvent(CrisisEvent.Type.MATCH_COMPLETED, report.headline()));
        fire(n -> n.matchCompleted(report));
        return report;
    }

    // =====================================================================
    //  Dashboard statistics (Collections + generics in action)
    // =====================================================================
    public DashboardStats stats() {
        List<EmergencyRequest> all = requestDao.findAll();
        List<EmergencyRequest> active = new ArrayList<>(all);
        CollectionUtils.removeIf(active, r -> !r.getStatus().isActive());
        List<Resource> res = resourceDao.findAll();
        List<Allocation> allocs = allocationDao.findAll();

        Map<ResourceType, Integer> supply = new EnumMap<>(ResourceType.class);
        Map<ResourceType, Integer> demand = new EnumMap<>(ResourceType.class);
        for (ResourceType t : ResourceType.values()) { supply.put(t, 0); demand.put(t, 0); }
        supply.putAll(CollectionUtils.sumBy(res, Resource::getType, Resource::getAvailableQuantity));
        demand.putAll(CollectionUtils.sumBy(active, EmergencyRequest::getResourceType,
                EmergencyRequest::remainingQuantity));

        Map<UrgencyLevel, Integer> byUrgency = new EnumMap<>(UrgencyLevel.class);
        for (UrgencyLevel u : UrgencyLevel.values()) byUrgency.put(u, 0);
        byUrgency.putAll(CollectionUtils.countBy(active, EmergencyRequest::getUrgency));

        int fulfilled = (int) all.stream().filter(r -> r.getStatus() == RequestStatus.FULFILLED).count();
        int people = active.stream().mapToInt(EmergencyRequest::getPeopleAffected).sum();
        int units = res.stream().mapToInt(Resource::getAvailableQuantity).sum();
        int dispatched = allocs.stream().mapToInt(Allocation::getQuantity).sum();

        return new DashboardStats(all.size(), active.size(), byUrgency.get(UrgencyLevel.CRITICAL), fulfilled,
                res.size(), units, orgDao.count(), allocs.size(), people, dispatched, supply, demand, byUrgency);
    }

    public MatchingEngine getEngine() { return engine; }
}
