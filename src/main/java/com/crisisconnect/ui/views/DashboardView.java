package com.crisisconnect.ui.views;

import com.crisisconnect.event.CrisisEvent;
import com.crisisconnect.event.EventBus;
import com.crisisconnect.model.EmergencyRequest;
import com.crisisconnect.model.ResourceType;
import com.crisisconnect.model.UrgencyLevel;
import com.crisisconnect.service.CrisisService;
import com.crisisconnect.service.DashboardStats;
import com.crisisconnect.ui.UiUtils;
import com.crisisconnect.ui.View;
import com.crisisconnect.util.TimeUtils;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/** Command-centre overview: KPIs, supply-vs-demand chart, urgency mix, live priority queue, activity feed. */
public class DashboardView implements View {

    private final CrisisService service;

    private final Label active = new Label(), critical = new Label(), people = new Label(),
            units = new Label(), dispatched = new Label(), rate = new Label();
    private final BarChart<String, Number> gapChart;
    private final PieChart urgencyPie = new PieChart();
    private final ListView<EmergencyRequest> queueList = new ListView<>();
    private final ListView<CrisisEvent> feed = new ListView<>();
    private final ScrollPane root;

    public DashboardView(CrisisService service) {
        this.service = service;

        HBox kpis = new HBox(14,
                UiUtils.kpi(active, "Active requests", "accent-blue"),
                UiUtils.kpi(critical, "Critical now", "accent-red"),
                UiUtils.kpi(people, "People affected", "accent-orange"),
                UiUtils.kpi(units, "Units in stock", "accent-teal"),
                UiUtils.kpi(dispatched, "Units dispatched", "accent-purple"),
                UiUtils.kpi(rate, "Fulfilment rate", "accent-green"));

        CategoryAxis x = new CategoryAxis();
        NumberAxis y = new NumberAxis();
        y.setLabel("units");
        gapChart = new BarChart<>(x, y);
        gapChart.setAnimated(false);
        gapChart.setLegendVisible(true);
        gapChart.setPrefHeight(300);
        gapChart.setCategoryGap(24);

        urgencyPie.setLabelsVisible(true);
        urgencyPie.setLegendVisible(false);
        urgencyPie.setPrefHeight(300);
        urgencyPie.setAnimated(false);

        queueList.setCellFactory(lv -> new QueueCell());
        queueList.setPrefHeight(330);
        queueList.setPlaceholder(new Label("No active requests - all clear!"));

        feed.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(CrisisEvent e, boolean empty) {
                super.updateItem(e, empty);
                if (empty || e == null) { setText(null); setGraphic(null); return; }
                Label time = new Label(TimeUtils.ago(e.time()));
                time.getStyleClass().add("muted");
                Label msg = new Label(e.message());
                msg.setWrapText(true);
                msg.setMaxWidth(330);
                Label type = new Label(e.type().name().replace('_', ' '));
                type.getStyleClass().add("feed-type");
                setGraphic(new VBox(2, new HBox(8, type, time), msg));
            }
        });
        feed.setPrefHeight(330);
        feed.setPlaceholder(new Label("Activity will appear here"));

        VBox gapCard = UiUtils.card("Supply vs Demand (gap analysis)", gapChart);
        VBox pieCard = UiUtils.card("Active requests by urgency", urgencyPie);
        VBox queueCard = UiUtils.card("Live priority queue  (PriorityQueue<EmergencyRequest>)", queueList);
        VBox feedCard = UiUtils.card("Activity feed", feed);

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(16);
        grid.add(gapCard, 0, 0);
        grid.add(pieCard, 1, 0);
        grid.add(queueCard, 0, 1);
        grid.add(feedCard, 1, 1);
        javafx.scene.layout.ColumnConstraints c1 = new javafx.scene.layout.ColumnConstraints();
        c1.setPercentWidth(60);
        javafx.scene.layout.ColumnConstraints c2 = new javafx.scene.layout.ColumnConstraints();
        c2.setPercentWidth(40);
        grid.getColumnConstraints().addAll(c1, c2);

        VBox content = new VBox(18, kpis, grid);
        content.setPadding(new Insets(22));
        root = new ScrollPane(content);
        root.setFitToWidth(true);
        root.getStyleClass().add("page");
    }

    @Override public String title() { return "Command Dashboard"; }

    @Override public Node root() { return root; }

    @Override
    public void refresh() {
        DashboardStats s = service.stats();
        active.setText(String.valueOf(s.activeRequests()));
        critical.setText(String.valueOf(s.criticalActive()));
        people.setText(String.format("%,d", s.peopleAffectedActive()));
        units.setText(String.format("%,d", s.unitsAvailable()));
        dispatched.setText(String.format("%,d", s.unitsDispatched()));
        rate.setText(String.format("%.0f%%", s.fulfilmentRate()));

        XYChart.Series<String, Number> supply = new XYChart.Series<>();
        supply.setName("Supply (available)");
        XYChart.Series<String, Number> demand = new XYChart.Series<>();
        demand.setName("Demand (still needed)");
        for (ResourceType t : ResourceType.values()) {
            supply.getData().add(new XYChart.Data<>(t.getLabel(), s.supplyByType().get(t)));
            demand.getData().add(new XYChart.Data<>(t.getLabel(), s.demandByType().get(t)));
        }
        gapChart.getData().setAll(List.of(supply, demand));

        urgencyPie.getData().clear();
        for (UrgencyLevel u : UrgencyLevel.values()) {
            int n = s.activeByUrgency().get(u);
            if (n > 0) {
                PieChart.Data d = new PieChart.Data(u.name() + " (" + n + ")", n);
                urgencyPie.getData().add(d);
                d.getNode().setStyle("-fx-pie-color: " + u.getColorHex() + ";");
            }
        }

        queueList.setItems(FXCollections.observableArrayList(service.priorityQueueSnapshot()));
        feed.setItems(FXCollections.observableArrayList(EventBus.get().recent()));
    }

    /** Custom ListCell: rank, urgency badge, title, score, fulfilment bar. */
    private static class QueueCell extends ListCell<EmergencyRequest> {
        @Override
        protected void updateItem(EmergencyRequest r, boolean empty) {
            super.updateItem(r, empty);
            if (empty || r == null) { setGraphic(null); setText(null); return; }
            Label rank = new Label("#" + (getIndex() + 1));
            rank.getStyleClass().add("rank");
            Label title = new Label(r.getTitle());
            title.getStyleClass().add("queue-title");
            Label meta = new Label(String.format("%s  •  needs %d %s  •  %,d people  •  %s",
                    r.getCity(), r.remainingQuantity(), r.getResourceType().getUnit(), r.getPeopleAffected(),
                    TimeUtils.ago(r.getCreatedAt())));
            meta.getStyleClass().add("muted");
            ProgressBar bar = new ProgressBar(r.fulfilmentPercent() / 100);
            bar.setPrefWidth(120);
            Label score = new Label(String.format("%.1f", r.priorityScore()));
            score.getStyleClass().add("score");
            VBox mid = new VBox(3, new HBox(8, UiUtils.urgencyBadge(r.getUrgency()), title), meta);
            HBox.setHgrow(mid, Priority.ALWAYS);
            VBox right = new VBox(4, score, bar);
            right.setAlignment(Pos.CENTER_RIGHT);
            HBox row = new HBox(12, rank, mid, right);
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
        }
    }
}
