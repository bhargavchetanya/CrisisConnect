package com.crisisconnect;

import com.crisisconnect.dao.DaoFactory;
import com.crisisconnect.db.DataSeeder;
import com.crisisconnect.db.DatabaseManager;
import com.crisisconnect.service.BackupService;
import com.crisisconnect.service.CrisisService;
import com.crisisconnect.service.CsvImportService;
import com.crisisconnect.service.MatchingEngine;
import com.crisisconnect.service.ReportService;
import com.crisisconnect.util.AppConfig;
import com.crisisconnect.util.LoggingConfig;

import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Wires all layers together once ("composition root").
 * Both the JavaFX app and the stand-alone API server start from here.
 */
public final class AppContext {

    private static final Logger LOG = Logger.getLogger(AppContext.class.getName());
    private static AppContext instance;

    private final CrisisService crisisService;
    private final ReportService reportService;
    private final BackupService backupService;
    private final CsvImportService csvImportService;

    private AppContext() {
        AppConfig cfg = AppConfig.get();
        DatabaseManager.getInstance().initSchema();
        if (cfg.getBoolean("db.seedSampleData", true)) DataSeeder.seedIfEmpty();

        MatchingEngine engine = new MatchingEngine(cfg.getDouble("match.maxRadiusKm", 180));
        crisisService = new CrisisService(DaoFactory.organizations(), DaoFactory.resources(),
                DaoFactory.requests(), DaoFactory.allocations(), engine);
        reportService = new ReportService(crisisService, Path.of(cfg.getString("export.dir", "exports")));
        backupService = new BackupService(crisisService, Path.of(cfg.getString("backup.dir", "backups")));
        csvImportService = new CsvImportService(crisisService);
        LOG.info("Application context ready");
    }

    public static synchronized AppContext init() {
        if (instance == null) {
            LoggingConfig.init();
            instance = new AppContext();
        }
        return instance;
    }

    public static AppContext get() {
        if (instance == null) throw new IllegalStateException("AppContext.init() has not been called");
        return instance;
    }

    public CrisisService crisis() { return crisisService; }
    public ReportService reports() { return reportService; }
    public BackupService backups() { return backupService; }
    public CsvImportService csvImport() { return csvImportService; }
}
