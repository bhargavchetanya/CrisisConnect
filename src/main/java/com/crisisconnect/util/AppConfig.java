package com.crisisconnect.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Loads application settings from a .properties file (Unit 3 - configuration files).
 * Order: built-in defaults (classpath app.properties) -> external config/app.properties overrides.
 * Singleton pattern - one shared configuration for the whole app.
 */
public final class AppConfig {

    private static final Logger LOG = Logger.getLogger(AppConfig.class.getName());
    private static final AppConfig INSTANCE = new AppConfig();

    private final Properties props = new Properties();

    private AppConfig() {
        try (InputStream in = AppConfig.class.getResourceAsStream("/app.properties")) {
            if (in != null) props.load(in);
        } catch (IOException e) {
            LOG.warning("Could not load default app.properties: " + e.getMessage());
        }
        Path external = Path.of("config", "app.properties");
        if (Files.exists(external)) {
            try (Reader reader = Files.newBufferedReader(external)) {
                props.load(reader);
                LOG.info("Loaded external configuration from " + external.toAbsolutePath());
            } catch (IOException e) {
                LOG.warning("Could not read " + external + ": " + e.getMessage());
            }
        }
    }

    public static AppConfig get() { return INSTANCE; }

    /**
     * Lookup order: JVM system property (-Dkey=value) -> environment variable -> properties file.
     * The environment variable name is the key in upper case with dots replaced by underscores,
     * e.g. {@code admin.password} -> {@code ADMIN_PASSWORD}. Cloud hosts such as Render inject
     * secrets this way, so they never have to be committed to Git.
     */
    public Optional<String> find(String key) {
        String sys = System.getProperty(key);
        if (sys != null) return Optional.of(sys);
        String env = System.getenv(key.toUpperCase().replace('.', '_').replace('-', '_'));
        if (env != null && !env.isBlank()) return Optional.of(env);
        return Optional.ofNullable(props.getProperty(key));
    }

    public String getString(String key, String def) { return find(key).orElse(def); }

    public int getInt(String key, int def) {
        return find(key).map(String::trim).map(v -> {
            try { return Integer.parseInt(v); } catch (NumberFormatException e) { return def; }
        }).orElse(def);
    }

    public double getDouble(String key, double def) {
        return find(key).map(String::trim).map(v -> {
            try { return Double.parseDouble(v); } catch (NumberFormatException e) { return def; }
        }).orElse(def);
    }

    public boolean getBoolean(String key, boolean def) {
        return find(key).map(String::trim).map(Boolean::parseBoolean).orElse(def);
    }
}
