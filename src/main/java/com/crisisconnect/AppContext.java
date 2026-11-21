package com.crisisconnect;

import com.crisisconnect.dao.DaoFactory;
import com.crisisconnect.db.DataSeeder;
import com.crisisconnect.db.DatabaseManager;
import com.crisisconnect.service.AuditService;
import com.crisisconnect.service.AuthService;
import com.crisisconnect.service.BackupService;
import com.crisisconnect.service.CrisisService;
import com.crisisconnect.service.CsvImportService;
import com.crisisconnect.service.EmailSender;
import com.crisisconnect.service.MatchingEngine;
import com.crisisconnect.service.NotificationService;
import com.crisisconnect.service.ReportService;
import com.crisisconnect.util.AppConfig;
import com.crisisconnect.util.LoggingConfig;

import java.nio.file.Path;
import java.util.Arrays;
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
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final AuthService authService;

    private AppContext() {
        AppConfig cfg = AppConfig.get();
        DatabaseManager.getInstance().initSchema();
        boolean seed = cfg.getBoolean("db.seedSampleData", true);
        if (seed) DataSeeder.seedIfEmpty();

        MatchingEngine engine = new MatchingEngine(cfg.getDouble("match.maxRadiusKm", 180));
        crisisService = new CrisisService(DaoFactory.organizations(), DaoFactory.resources(),
                DaoFactory.requests(), DaoFactory.allocations(), engine);
        reportService = new ReportService(crisisService, Path.of(cfg.getString("export.dir", "exports")));
        backupService = new BackupService(crisisService, Path.of(cfg.getString("backup.dir", "backups")));
        csvImportService = new CsvImportService(crisisService);

        // ---- v2: notifications, audit log, accounts ----
        EmailSender email = new EmailSender(cfg.getString("brevo.api.key", ""), cfg.getString("email.from", ""),
                cfg.getString("email.from.name", "CrisisConnect"));
        notificationService = new NotificationService(DaoFactory.notifications(), DaoFactory.organizations(), email,
                publicUrl(cfg), cfg.getDouble("notify.critical.radiusKm", 60));
        crisisService.addNotifier(notificationService);           // observer: SMS / WhatsApp simulation
        auditService = new AuditService(DaoFactory.audit());
        authService = new AuthService(DaoFactory.users(), DaoFactory.organizations(), notificationService, auditService,
                Arrays.asList(cfg.getString("admin.emails", "").split("[,; ]+")),
                cfg.getInt("auth.session.hours", 24), cfg.getBoolean("auth.show.dev.codes", true));
        authService.bootstrapAdmins(cfg.getString("admin.password", ""));
        if (seed && cfg.getBoolean("demo.accounts", true)) authService.seedDemoCoordinator();
        authService.purgeExpiredSessions();
        LOG.info("Application context ready (e-mail delivery: "
                + (email.isConfigured() ? "Brevo" : "simulated") + ", public URL " + notificationService.publicUrl() + ")");
    }

    /** APP_PUBLIC_URL, else Render's automatic RENDER_EXTERNAL_URL, else localhost. */
    private static String publicUrl(AppConfig cfg) {
        String url = cfg.getString("app.public.url", "");
        if (url.isBlank()) url = cfg.getString("render.external.url", "");
        if (url.isBlank()) url = "http://localhost:" + cfg.getInt("api.port", 8080);
        return url.trim();
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
    public NotificationService notifications() { return notificationService; }
    public AuditService audit() { return auditService; }
    public AuthService auth() { return authService; }
}
