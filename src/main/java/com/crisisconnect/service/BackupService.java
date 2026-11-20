package com.crisisconnect.service;

import com.crisisconnect.dao.DaoFactory;
import com.crisisconnect.db.JdbcTemplate;
import com.crisisconnect.db.TransactionManager;
import com.crisisconnect.event.CrisisEvent;
import com.crisisconnect.event.EventBus;
import com.crisisconnect.exception.AllocationFailedException;
import com.crisisconnect.model.Allocation;
import com.crisisconnect.model.EmergencyRequest;
import com.crisisconnect.model.Organization;
import com.crisisconnect.model.Resource;
import com.crisisconnect.util.TimeUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Offline backup & restore using Java Object Serialization (Unit 3 - byte streams).
 * Useful when the network is down in a disaster zone: copy the .ser file on a pen-drive.
 */
public class BackupService {

    private static final Logger LOG = Logger.getLogger(BackupService.class.getName());

    private final CrisisService service;
    private final Path backupDir;

    public BackupService(CrisisService service, Path backupDir) {
        this.service = service;
        this.backupDir = backupDir;
    }

    /** SERIALIZATION: object graph -> bytes on disk. */
    public Path backup() throws IOException {
        Files.createDirectories(backupDir);
        Path file = backupDir.resolve("snapshot-" + LocalDateTime.now().format(TimeUtils.FILE_STAMP) + ".ser");
        DataSnapshot snap = new DataSnapshot(service.organizations(), service.resources(),
                service.requests(), service.allocations());
        try (ObjectOutputStream out = new ObjectOutputStream(
                new BufferedOutputStream(Files.newOutputStream(file)))) {
            out.writeObject(snap);
        }
        LOG.info("Backup written: " + file + " (" + snap.summary() + ", " + Files.size(file) + " bytes)");
        return file;
    }

    /** DESERIALIZATION: bytes on disk -> object graph. */
    public DataSnapshot read(Path file) throws IOException, ClassNotFoundException {
        try (ObjectInputStream in = new ObjectInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            DataSnapshot snap = (DataSnapshot) in.readObject();
            snap.setLoadedFrom(file.getFileName().toString());
            return snap;
        }
    }

    /**
     * Replaces the whole database with a snapshot - in ONE transaction.
     * Primary keys are regenerated, so old->new ids are re-mapped with HashMaps.
     */
    public void restore(DataSnapshot snap) throws AllocationFailedException {
        JdbcTemplate jdbc = new JdbcTemplate();
        TransactionManager.inTransaction(() -> {
            jdbc.update("DELETE FROM allocations");
            jdbc.update("DELETE FROM emergency_requests");
            jdbc.update("DELETE FROM resources");
            jdbc.update("DELETE FROM organizations");

            Map<Integer, Integer> orgIds = new HashMap<>(), resIds = new HashMap<>(), reqIds = new HashMap<>();
            for (Organization o : snap.getOrganizations()) {
                int old = o.getId();
                orgIds.put(old, DaoFactory.organizations().save(o).getId());
            }
            for (Resource r : snap.getResources()) {
                int old = r.getId();
                r.setOrganizationId(orgIds.get(r.getOrganizationId()));
                resIds.put(old, DaoFactory.resources().save(r).getId());
            }
            for (EmergencyRequest q : snap.getRequests()) {
                int old = q.getId();
                reqIds.put(old, DaoFactory.requests().save(q).getId());
            }
            for (Allocation a : snap.getAllocations()) {
                a.setRequestId(reqIds.get(a.getRequestId()));
                a.setResourceId(resIds.get(a.getResourceId()));
                DaoFactory.allocations().save(a);
            }
            return null;
        });
        LOG.info("Database restored from snapshot: " + snap.summary());
        EventBus.get().publish(new CrisisEvent(CrisisEvent.Type.DATA_RESTORED, "Restored " + snap.summary()));
    }
}
