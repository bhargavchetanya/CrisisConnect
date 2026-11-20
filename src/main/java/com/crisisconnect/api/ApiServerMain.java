package com.crisisconnect.api;

import com.crisisconnect.AppContext;
import com.crisisconnect.util.AppConfig;

import java.nio.file.Path;

/** Runs ONLY the REST API + website (no JavaFX window). Command: mvn exec:java */
public final class ApiServerMain {

    public static void main(String[] args) throws Exception {
        AppContext ctx = AppContext.init();
        AppConfig cfg = AppConfig.get();
        ApiServer server = new ApiServer(ctx.crisis(), cfg.getInt("api.port", 8080),
                Path.of(cfg.getString("api.webRoot", "website")));
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        System.out.println("CrisisConnect API running -> open http://localhost:" + cfg.getInt("api.port", 8080));
        Thread.currentThread().join();
    }
}
