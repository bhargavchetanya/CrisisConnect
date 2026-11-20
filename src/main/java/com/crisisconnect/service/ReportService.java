package com.crisisconnect.service;

import com.crisisconnect.model.Allocation;
import com.crisisconnect.model.EmergencyRequest;
import com.crisisconnect.model.Resource;
import com.crisisconnect.model.ResourceType;
import com.crisisconnect.model.UrgencyLevel;
import com.crisisconnect.util.TimeUtils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.logging.Logger;

/**
 * Generates CSV exports and a text situation report (Unit 3 - character streams).
 */
public class ReportService {

    private static final Logger LOG = Logger.getLogger(ReportService.class.getName());

    private final CrisisService service;
    private final Path exportDir;

    public ReportService(CrisisService service, Path exportDir) {
        this.service = service;
        this.exportDir = exportDir;
    }

    /** Escapes a value for CSV (quotes + doubled quotes when needed). */
    static String csv(Object value) {
        String s = value == null ? "" : value.toString();
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private Path newFile(String prefix, String ext) throws IOException {
        Files.createDirectories(exportDir);
        return exportDir.resolve(prefix + "-" + LocalDateTime.now().format(TimeUtils.FILE_STAMP) + ext);
    }

    public Path exportRequestsCsv() throws IOException {
        Path file = newFile("requests", ".csv");
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("id,title,disaster,resource,needed,fulfilled,people,urgency,priority,city,status,source,created");
            w.newLine();
            for (EmergencyRequest r : service.requests()) {
                w.write(String.join(",", csv(r.getId()), csv(r.getTitle()), csv(r.getDisasterType()),
                        csv(r.getResourceType()), csv(r.getQuantityNeeded()), csv(r.getQuantityFulfilled()),
                        csv(r.getPeopleAffected()), csv(r.getUrgency()), csv(r.priorityScore()), csv(r.getCity()),
                        csv(r.getStatus()), csv(r.getSource()), csv(r.getCreatedAt())));
                w.newLine();
            }
        }
        LOG.info("Exported requests to " + file);
        return file;
    }

    public Path exportAllocationsCsv() throws IOException {
        Path file = newFile("dispatches", ".csv");
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("id,request,resource,organization,quantity,distance_km,eta_min,city,time");
            w.newLine();
            for (Allocation a : service.allocations()) {
                w.write(String.join(",", csv(a.getId()), csv(a.getRequestTitle()), csv(a.getResourceName()),
                        csv(a.getOrganizationName()), csv(a.getQuantity()), csv(a.getDistanceKm()),
                        csv(a.etaMinutes()), csv(a.getCity()), csv(a.getCreatedAt())));
                w.newLine();
            }
        }
        LOG.info("Exported allocations to " + file);
        return file;
    }

    /** Builds a human readable SITREP (situation report). */
    public String buildSituationReport() {
        DashboardStats s = service.stats();
        StringBuilder sb = new StringBuilder();
        String line = "=".repeat(64);
        sb.append(line).append('\n')
          .append("  CRISISCONNECT - SITUATION REPORT\n")
          .append("  Generated: ").append(TimeUtils.display(LocalDateTime.now())).append('\n')
          .append(line).append("\n\n");

        sb.append(String.format("Requests        : %d total, %d active, %d fulfilled (%.1f%%)%n",
                s.totalRequests(), s.activeRequests(), s.fulfilledRequests(), s.fulfilmentRate()));
        sb.append(String.format("Critical active : %d%n", s.criticalActive()));
        sb.append(String.format("People affected : %,d (active requests)%n", s.peopleAffectedActive()));
        sb.append(String.format("Organizations   : %d   Resources: %d   Units in stock: %,d%n",
                s.organizations(), s.resourceCount(), s.unitsAvailable()));
        sb.append(String.format("Dispatches      : %d (%,d units delivered)%n%n", s.allocations(), s.unitsDispatched()));

        sb.append("SUPPLY vs DEMAND (gap analysis)\n");
        sb.append(String.format("  %-18s %10s %10s %10s%n", "Resource", "Supply", "Demand", "Gap"));
        for (ResourceType t : ResourceType.values()) {
            int sup = s.supplyByType().get(t), dem = s.demandByType().get(t);
            sb.append(String.format("  %-18s %10d %10d %10s%n", t.getLabel(), sup, dem,
                    sup >= dem ? "OK" : "SHORT " + (dem - sup)));
        }

        sb.append("\nACTIVE REQUESTS BY URGENCY\n");
        for (UrgencyLevel u : UrgencyLevel.values()) {
            sb.append(String.format("  %-9s %s %d%n", u, "#".repeat(s.activeByUrgency().get(u)), s.activeByUrgency().get(u)));
        }

        sb.append("\nTOP 5 PRIORITY REQUESTS\n");
        List<EmergencyRequest> top = service.priorityQueueSnapshot();
        for (int i = 0; i < Math.min(5, top.size()); i++) {
            EmergencyRequest r = top.get(i);
            sb.append(String.format("  %d. [%5.1f] %-38s %s, short %d %s%n", i + 1, r.priorityScore(),
                    r.getTitle(), r.getCity(), r.remainingQuantity(), r.getResourceType().getUnit()));
        }

        sb.append("\nLOW STOCK ALERTS (< 20% left)\n");
        boolean any = false;
        for (Resource r : service.resources()) {
            if (r.getTotalCapacity() > 0 && r.utilization() > 0.8) {
                sb.append(String.format("  ! %s (%s) - %d / %d %s left%n", r.getName(), r.getCity(),
                        r.getAvailableQuantity(), r.getTotalCapacity(), r.getUnit()));
                any = true;
            }
        }
        if (!any) sb.append("  none\n");
        sb.append('\n').append(line).append('\n');
        return sb.toString();
    }

    public Path saveSituationReport() throws IOException {
        Path file = newFile("sitrep", ".txt");
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            pw.print(buildSituationReport());
        }
        LOG.info("Situation report written to " + file);
        return file;
    }
}
