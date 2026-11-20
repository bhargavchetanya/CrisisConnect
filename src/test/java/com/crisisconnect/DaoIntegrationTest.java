package com.crisisconnect;

import com.crisisconnect.dao.DaoFactory;
import com.crisisconnect.db.DatabaseManager;
import com.crisisconnect.db.TransactionManager;
import com.crisisconnect.exception.AllocationFailedException;
import com.crisisconnect.model.Organization;
import com.crisisconnect.model.OrganizationType;
import com.crisisconnect.model.Resource;
import com.crisisconnect.model.ResourceFactory;
import com.crisisconnect.model.ResourceType;
import com.crisisconnect.util.CityDirectory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the real JDBC DAOs against a temporary SQLite file. */
class DaoIntegrationTest {

    @TempDir
    static Path tmp;

    @BeforeAll
    static void setup() {
        DatabaseManager.overrideUrl("jdbc:sqlite:" + tmp.resolve("test.db"));
        DatabaseManager.getInstance().initSchema();
    }

    private static Resource newResource(int qty) {
        Organization o = DaoFactory.organizations().save(new Organization("Org" + System.nanoTime(),
                OrganizationType.NGO, null, null, CityDirectory.require("Patiala")));
        return DaoFactory.resources().save(ResourceFactory.create(ResourceType.FOOD, o.getId(), "Rice", qty,
                CityDirectory.require("Patiala")));
    }

    @Test
    void crudRoundTrip() {
        Resource r = newResource(100);
        Resource loaded = DaoFactory.resources().findById(r.getId()).orElseThrow();
        assertEquals("Rice", loaded.getName());
        assertEquals(ResourceType.FOOD, loaded.getType());
        assertTrue(DaoFactory.resources().updateQuantity(r.getId(), 40));
        assertEquals(40, DaoFactory.resources().findById(r.getId()).orElseThrow().getAvailableQuantity());
        assertTrue(DaoFactory.resources().delete(r.getId()));
        assertTrue(DaoFactory.resources().findById(r.getId()).isEmpty());
    }

    @Test
    void failedTransactionIsRolledBack() {
        Resource r = newResource(100);
        assertThrows(AllocationFailedException.class, () -> TransactionManager.inTransaction(() -> {
            DaoFactory.resources().updateQuantity(r.getId(), 0);
            throw new IllegalStateException("simulated crash after update");
        }));
        assertEquals(100, DaoFactory.resources().findById(r.getId()).orElseThrow().getAvailableQuantity(),
                "update must be rolled back");
    }
}
