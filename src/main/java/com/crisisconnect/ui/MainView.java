package com.crisisconnect.ui;

import com.crisisconnect.AppContext;
import com.crisisconnect.event.CrisisEvent;
import com.crisisconnect.event.EventBus;
import com.crisisconnect.ui.views.DashboardView;
import com.crisisconnect.ui.views.MatchCenterView;
import com.crisisconnect.ui.views.OrganizationsView;
import com.crisisconnect.ui.views.ReportsView;
import com.crisisconnect.ui.views.RequestsView;
import com.crisisconnect.ui.views.ResourcesView;
import com.crisisconnect.util.AppConfig;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Main window layout:
 * <pre>
 * StackPane (for toast overlay)
 *   └─ BorderPane
 *        ├─ top    : header bar (HBox)  - title, live clock, API link
 *        ├─ left   : sidebar (VBox)     - navigation ToggleButtons
 *        └─ center : current View
 * </pre>
 */
public class MainView {

    private final StackPane root = new StackPane();
    private final BorderPane frame = new BorderPane();
    private final Toast toast;
    private final Map<String, View> views = new LinkedHashMap<>();
    private final Label pageTitle = new Label();
    private final MatchCenterView matchView;
    private View current;

    public MainView(AppContext ctx, HostServices host, boolean apiRunning) {
        root.getChildren().add(frame);
        toast = new Toast(root);

        views.put("Dashboard", new DashboardView(ctx.crisis()));
        views.put("Requests", new RequestsView(ctx.crisis(), toast));
        views.put("Resources", new ResourcesView(ctx.crisis(), toast));
        matchView = new MatchCenterView(ctx.crisis(), toast);
        views.put("Match Center", matchView);
        views.put("Organizations", new OrganizationsView(ctx.crisis(), toast));
        views.put("Reports", new ReportsView(ctx, toast));

        frame.setTop(buildHeader(host, apiRunning));
        frame.setLeft(buildSidebar());
        show("Dashboard");

        // Observer: react to events coming from ANY thread (e.g. the website via REST API)
        EventBus.get().subscribe(e -> Platform.runLater(() -> onEvent(e)));
    }

    private HBox buildHeader(HostServices host, boolean apiRunning) {
        Circle pulse = new Circle(6);
        pulse.getStyleClass().add("pulse");
        FadeTransition ft = new FadeTransition(Duration.seconds(0.9), pulse);
        ft.setFromValue(1);
        ft.setToValue(0.25);
        ft.setAutoReverse(true);
        ft.setCycleCount(FadeTransition.INDEFINITE);
        ft.play();

        Label live = new Label("LIVE");
        live.getStyleClass().add("live");
        pageTitle.getStyleClass().add("page-title");

        Label clock = new Label();
        clock.getStyleClass().add("clock");
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("EEE dd MMM  HH:mm:ss");
        Timeline tick = new Timeline(new KeyFrame(Duration.seconds(1),
                e -> clock.setText(LocalDateTime.now().format(fmt))));
        tick.setCycleCount(Timeline.INDEFINITE);
        tick.play();
        clock.setText(LocalDateTime.now().format(fmt));

        HBox header = new HBox(12, pulse, live, pageTitle, UiUtils.hSpacer());
        if (apiRunning) {
            String url = "http://localhost:" + AppConfig.get().getInt("api.port", 8080) + "/";
            Hyperlink link = new Hyperlink("Web portal: " + url);
            link.getStyleClass().add("api-link");
            link.setOnAction(e -> host.showDocument(url));
            header.getChildren().add(link);
        }
        Button refresh = new Button("⟳ Refresh");
        refresh.setOnAction(e -> current.refresh());
        header.getChildren().addAll(clock, refresh);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(14, 22, 14, 22));
        header.getStyleClass().add("header");
        return header;
    }

    private VBox buildSidebar() {
        Label logo = new Label("Crisis");
        logo.getStyleClass().add("logo");
        Label logo2 = new Label("Connect");
        logo2.getStyleClass().add("logo-accent");
        HBox brand = new HBox(logo, logo2);
        Label tag = new Label("Emergency Resource\nCoordination System");
        tag.getStyleClass().add("tagline");

        VBox nav = new VBox(6);
        ToggleGroup group = new ToggleGroup();
        String[] icons = {"◉", "⚠", "▣", "⇄", "⌂", "▤"};
        int i = 0;
        for (String name : views.keySet()) {
            ToggleButton b = new ToggleButton(icons[i++] + "   " + name);
            b.setToggleGroup(group);
            b.getStyleClass().add("nav-button");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setOnAction(e -> { show(name); b.setSelected(true); });
            if (name.equals("Dashboard")) b.setSelected(true);
            nav.getChildren().add(b);
        }

        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        Label footer = new Label("UCS553 • Enterprise Web Application\nJava 17 • JavaFX • JDBC • SQLite");
        footer.getStyleClass().add("sidebar-footer");

        VBox side = new VBox(18, new VBox(4, brand, tag), nav, grow, footer);
        side.setPadding(new Insets(22, 14, 18, 14));
        side.setPrefWidth(225);
        side.getStyleClass().add("sidebar");
        return side;
    }

    private void show(String name) {
        current = views.get(name);
        current.refresh();
        pageTitle.setText(current.title());
        frame.setCenter(current.root());
    }

    private void onEvent(CrisisEvent e) {
        switch (e.type()) {
            case REQUEST_CREATED -> {
                if (e.message().contains("via WEB")) toast.show("New SOS from web portal", e.message(), Toast.Kind.ALERT);
                else if (e.message().startsWith("[CRITICAL]")) toast.show("Critical request", e.message(), Toast.Kind.ALERT);
            }
            case DATA_RESTORED, DATA_IMPORTED -> toast.show("Data updated", e.message(), Toast.Kind.INFO);
            default -> { }
        }
        if (current != null) current.refresh();   // keep the visible screen live
    }

    public void shutdown() { matchView.stopTimers(); }

    public StackPane getRoot() { return root; }
}
