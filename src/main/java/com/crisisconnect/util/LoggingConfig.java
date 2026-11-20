package com.crisisconnect.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Configures java.util.logging (Unit 3 - logging frameworks):
 * a console handler + a rotating file handler (logs/crisisconnect-N.log).
 */
public final class LoggingConfig {

    private static boolean initialised = false;

    private LoggingConfig() { }

    /** Custom one-line formatter: 2026-10-14 10:22:01 [INFO ] CrisisService - message */
    static class OneLineFormatter extends Formatter {
        private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        @Override
        public String format(LogRecord r) {
            String src = r.getLoggerName() == null ? "" :
                    r.getLoggerName().substring(r.getLoggerName().lastIndexOf('.') + 1);
            String line = String.format("%s [%-7s] %-22s - %s%n",
                    LocalDateTime.now().format(TS), r.getLevel().getName(), src, formatMessage(r));
            if (r.getThrown() != null) line += "    caused by: " + r.getThrown() + System.lineSeparator();
            return line;
        }
    }

    public static synchronized void init() {
        if (initialised) return;
        AppConfig cfg = AppConfig.get();
        Level level = Level.parse(cfg.getString("log.level", "INFO"));

        Logger root = Logger.getLogger("");
        for (Handler h : root.getHandlers()) root.removeHandler(h);
        root.setLevel(level);

        ConsoleHandler console = new ConsoleHandler();
        console.setFormatter(new OneLineFormatter());
        console.setLevel(level);
        root.addHandler(console);

        try {
            Path dir = Path.of(cfg.getString("log.dir", "logs"));
            Files.createDirectories(dir);
            // 1 MB per file, keep 3 rotating files, append mode
            FileHandler file = new FileHandler(dir.resolve("crisisconnect-%g.log").toString(),
                    1_000_000, 3, true);
            file.setFormatter(new OneLineFormatter());
            file.setLevel(Level.ALL);
            root.addHandler(file);
        } catch (IOException e) {
            root.warning("File logging disabled: " + e.getMessage());
        }
        initialised = true;
    }
}
