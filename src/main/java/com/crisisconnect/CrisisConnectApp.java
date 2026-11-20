package com.crisisconnect;

import com.crisisconnect.api.ApiServer;
import com.crisisconnect.ui.MainView;
import com.crisisconnect.util.AppConfig;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JavaFX entry point. Demonstrates the Application life-cycle (Unit 5):
 *   init()  -> runs on the launcher thread: config, logging, database, API server
 *   start() -> runs on the JavaFX Application Thread: builds Stage + Scene graph
 *   stop()  -> called when the window closes: release resources
 */
public class CrisisConnectApp extends Application {

    private static final Logger LOG = Logger.getLogger(CrisisConnectApp.class.getName());

    private AppContext ctx;
    private ApiServer api;
    private MainView mainView;
    private Exception startupError;

    @Override
    public void init() {
        try {
            ctx = AppContext.init();
            AppConfig cfg = AppConfig.get();
            if (cfg.getBoolean("api.enabled", true)) {
                api = new ApiServer(ctx.crisis(), cfg.getInt("api.port", 8080),
                        Path.of(cfg.getString("api.webRoot", "website")));
                try {
                    api.start();
                } catch (Exception e) {
                    LOG.warning("API server not started (port busy?): " + e.getMessage());
                    api = null;
                }
            }
            LOG.info("init() complete");
        } catch (Exception e) {
            startupError = e;
            LOG.log(Level.SEVERE, "Startup failed", e);
        }
    }

    @Override
    public void start(Stage stage) {
        if (startupError != null) {
            Alert a = new Alert(Alert.AlertType.ERROR, startupError.getMessage());
            a.setHeaderText("CrisisConnect could not start");
            a.showAndWait();
            Platform.exit();
            return;
        }
        mainView = new MainView(ctx, getHostServices(), api != null);
        Scene scene = new Scene(mainView.getRoot(), 1380, 860);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/styles.css")).toExternalForm());
        stage.setTitle("CrisisConnect – Intelligent Emergency Resource Coordination");
        stage.setMinWidth(1100);
        stage.setMinHeight(700);
        stage.setScene(scene);
        stage.show();
        LOG.info("start() - main window shown");
    }

    @Override
    public void stop() {
        if (mainView != null) mainView.shutdown();
        if (api != null) api.stop();
        LOG.info("stop() - CrisisConnect closed cleanly");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
