package com.crisisconnect.service;

import com.crisisconnect.event.CrisisEvent;
import com.crisisconnect.event.EventBus;
import com.crisisconnect.exception.ValidationException;
import com.crisisconnect.model.DisasterType;
import com.crisisconnect.model.EmergencyRequest;
import com.crisisconnect.model.ResourceType;
import com.crisisconnect.model.UrgencyLevel;
import com.crisisconnect.util.CityDirectory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Bulk-imports emergency requests sent by field teams as CSV (BufferedReader - character stream).
 * Bad lines do NOT stop the import - each error is collected and reported.
 *
 * Format: title,disasterType,resourceType,quantity,peopleAffected,urgency,city,phone,vulnerable
 */
public class CsvImportService {

    private static final Logger LOG = Logger.getLogger(CsvImportService.class.getName());

    public record ImportResult(int imported, List<String> errors) { }

    private final CrisisService service;

    public CsvImportService(CrisisService service) { this.service = service; }

    public ImportResult importRequests(Path file) throws IOException {
        int ok = 0;
        List<String> errors = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = br.readLine()) != null) {
                lineNo++;
                if (lineNo == 1 && line.toLowerCase().startsWith("title")) continue; // header
                if (line.isBlank() || line.startsWith("#")) continue;
                try {
                    service.createRequest(parse(line));
                    ok++;
                } catch (ValidationException e) {
                    errors.add("Line " + lineNo + ": " + String.join(", ", e.getErrors()));
                } catch (NumberFormatException e) {
                    errors.add("Line " + lineNo + ": quantity and people must be whole numbers");
                } catch (IllegalArgumentException e) {
                    errors.add("Line " + lineNo + ": " + e.getMessage());
                }
            }
        }
        LOG.info("CSV import from " + file.getFileName() + ": " + ok + " imported, " + errors.size() + " errors");
        if (ok > 0) EventBus.get().publish(new CrisisEvent(CrisisEvent.Type.DATA_IMPORTED,
                ok + " field requests imported from " + file.getFileName()));
        return new ImportResult(ok, errors);
    }

    static EmergencyRequest parse(String line) {
        String[] p = line.split(",", -1);
        if (p.length < 7) throw new IllegalArgumentException("expected at least 7 columns, found " + p.length);
        EmergencyRequest r = new EmergencyRequest(
                p[0].trim(),
                DisasterType.valueOf(p[1].trim().toUpperCase()),
                ResourceType.valueOf(p[2].trim().toUpperCase()),
                Integer.parseInt(p[3].trim()),
                Integer.parseInt(p[4].trim()),
                UrgencyLevel.valueOf(p[5].trim().toUpperCase()),
                CityDirectory.require(p[6].trim()));
        if (p.length > 7) r.setContactPhone(p[7].trim());
        if (p.length > 8) r.setVulnerableGroups(Boolean.parseBoolean(p[8].trim()));
        r.setSource("CSV");
        return r;
    }
}
