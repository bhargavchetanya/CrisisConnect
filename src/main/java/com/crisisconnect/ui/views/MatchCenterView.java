package com.crisisconnect.ui.views;

import com.crisisconnect.model.Allocation;
import com.crisisconnect.service.AllocationPlan;
import com.crisisconnect.service.CrisisService;
import com.crisisconnect.service.MatchReport;
import com.crisisconnect.service.MatchResult;
import com.crisisconnect.ui.Toast;
import com.crisisconnect.ui.UiUtils;
import com.crisisconnect.ui.View;
import com.crisisconnect.util.TimeUtils;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.List;

/**
 * Runs the matching engine. The heavy work runs in a background javafx.concurrent.Task
 * so the UI never freezes; results come back on the JavaFX Application Thread.
 */
public class MatchCenterView implements View {

    private final CrisisService service;
    private final Toast toast;
    private final TextArea console = new TextArea();
    private final TableView<Allocation> table = new TableView<>();
    private final Label summary = new Label("Press RUN SMART MATCH to dispatch resources to the most urgent requests first.");
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Button run = new Button("▶  RUN SMART MATCH");
    private final CheckBox auto = new CheckBox("Auto-match every 30 s");
    private final Timeline autoTimer;
    private final VBox root;

    public MatchCenterView(CrisisService service, Toast toast) {
        this.service = service;
        this.toast = toast;

        run.getStyleClass().addAll("btn-danger", "btn-big");
        run.setOnAction(e -> runMatching());
        Button preview = new Button("Preview plan (dry run)");
        preview.setOnAction(e -> previewPlan());
        spinner.setVisible(false);
        spinner.setPrefSize(28, 28);

        autoTimer = new Timeline(new KeyFrame(Duration.seconds(30), e -> runMatching()));
        autoTimer.setCycleCount(Timeline.INDEFINITE);
        auto.selectedProperty().addListener((o, was, on) -> {
            if (on) { autoTimer.play(); toast.show("Auto-match ON", "Engine will run every 30 seconds", Toast.Kind.INFO); }
            else autoTimer.stop();
        });

        summary.getStyleClass().add("summary");
        summary.setWrapText(true);

        Label algo = new Label("""
                1. Active requests -> PriorityQueue (score = urgency + log(people) + waiting time + vulnerable bonus)
                2. Poll the most urgent request
                3. Candidate resources of the same type, in stock, within the configured radius
                4. Sort by polymorphic matchCost() (distance, expiry, resource nature)
                5. Dispatch greedily - each request committed in its own JDBC transaction (rollback on failure)""");
        algo.getStyleClass().add("algo");

        HBox controls = new HBox(14, run, preview, spinner, UiUtils.hSpacer(), auto);
        controls.setAlignment(Pos.CENTER_LEFT);

        console.setEditable(false);
        console.getStyleClass().add("console");
        console.setPrefRowCount(16);
        console.setWrapText(true);
        console.setText("> CrisisConnect matching engine ready.\n");

        table.getColumns().addAll(List.of(
                UiUtils.column("#", Allocation::getId, 50),
                UiUtils.column("Request", Allocation::getRequestTitle, 240),
                UiUtils.column("Resource", Allocation::getResourceName, 190),
                UiUtils.column("From", Allocation::getOrganizationName, 180),
                UiUtils.column("Qty", Allocation::getQuantity, 60),
                UiUtils.column("Distance km", Allocation::getDistanceKm, 95),
                UiUtils.column("ETA min", Allocation::etaMinutes, 75),
                UiUtils.column("To", Allocation::getCity, 90),
                UiUtils.column("When", (Allocation a) -> TimeUtils.ago(a.getCreatedAt()), 90)));
        table.setPlaceholder(new Label("No dispatches yet"));
        table.setPrefHeight(260);

        VBox top = UiUtils.card("Smart Match Engine", algo, controls, summary);
        VBox log = UiUtils.card("Engine console", console);
        VBox hist = UiUtils.card("Dispatch history", table);
        VBox.setVgrow(console, Priority.ALWAYS);
        VBox.setVgrow(table, Priority.ALWAYS);
        HBox middle = new HBox(16, log, hist);
        HBox.setHgrow(log, Priority.ALWAYS);
        HBox.setHgrow(hist, Priority.ALWAYS);
        log.setMinWidth(480);
        log.setPrefWidth(560);
        hist.setPrefWidth(600);
        VBox.setVgrow(middle, Priority.ALWAYS);

        root = new VBox(16, top, middle);
        root.setPadding(new Insets(22));
        root.getStyleClass().add("page");
    }

    private void previewPlan() {
        MatchResult r = service.previewMatching();
        StringBuilder sb = new StringBuilder("\n> DRY RUN (nothing saved) - " + r.plans().size() + " dispatches planned\n");
        for (AllocationPlan p : r.plans()) {
            sb.append(String.format("   %-40s <- %4d %-9s %s (%.1f km)%n", p.request().getTitle(), p.quantity(),
                    p.resource().getUnit(), p.resource().getName(), p.distanceKm()));
        }
        r.shortfalls().forEach((id, shortBy) -> sb.append("   ! request #").append(id)
                .append(" will still be short by ").append(shortBy).append('\n'));
        console.appendText(sb.toString());
    }

    private void runMatching() {
        if (run.isDisabled()) return;
        run.setDisable(true);
        spinner.setVisible(true);
        console.appendText("\n> Running smart match at " + TimeUtils.display(java.time.LocalDateTime.now()) + " ...\n");

        Task<MatchReport> task = new Task<>() {
            @Override
            protected MatchReport call() {
                return service.runMatching();     // background thread: JDBC + algorithm
            }
        };
        task.setOnSucceeded(e -> {                 // back on the JavaFX thread
            MatchReport rep = task.getValue();
            for (String line : rep.log()) console.appendText(line + "\n");
            console.appendText("> DONE in " + rep.durationMs() + " ms - " + rep.headline() + "\n");
            summary.setText(rep.headline() + (rep.failedTransactions() > 0
                    ? "   |   " + rep.failedTransactions() + " transaction(s) rolled back" : ""));
            toast.show("Smart match complete", rep.headline(),
                    rep.unmet() > 0 ? Toast.Kind.WARNING : Toast.Kind.SUCCESS);
            finish();
        });
        task.setOnFailed(e -> {
            console.appendText("> ERROR: " + task.getException().getMessage() + "\n");
            UiUtils.error("Matching failed", String.valueOf(task.getException().getMessage()));
            finish();
        });
        Thread t = new Thread(task, "match-engine");
        t.setDaemon(true);
        t.start();
    }

    private void finish() {
        run.setDisable(false);
        spinner.setVisible(false);
        refresh();
    }

    public void stopTimers() { autoTimer.stop(); }

    @Override public String title() { return "Match Center"; }

    @Override public Node root() { return root; }

    @Override
    public void refresh() {
        table.setItems(FXCollections.observableArrayList(service.allocations()));
    }
}
