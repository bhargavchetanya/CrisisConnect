package com.crisisconnect.ui.views;

import com.crisisconnect.exception.CrisisConnectException;
import com.crisisconnect.exception.ValidationException;
import com.crisisconnect.model.Allocation;
import com.crisisconnect.model.DisasterType;
import com.crisisconnect.model.EmergencyRequest;
import com.crisisconnect.model.RequestStatus;
import com.crisisconnect.model.ResourceType;
import com.crisisconnect.model.UrgencyLevel;
import com.crisisconnect.service.CrisisService;
import com.crisisconnect.ui.Toast;
import com.crisisconnect.ui.UiUtils;
import com.crisisconnect.ui.View;
import com.crisisconnect.util.CityDirectory;
import com.crisisconnect.util.TimeUtils;
import com.crisisconnect.util.Validator;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/** Raise new emergency requests (with live validation) and browse / filter / cancel existing ones. */
public class RequestsView implements View {

    private final CrisisService service;
    private final Toast toast;
    private final ObservableList<EmergencyRequest> data = FXCollections.observableArrayList();
    private final FilteredList<EmergencyRequest> filtered = new FilteredList<>(data, r -> true);
    private final TableView<EmergencyRequest> table = new TableView<>();
    private final BorderPane root = new BorderPane();

    // form controls
    private final TextField title = new TextField();
    private final ComboBox<DisasterType> disaster = new ComboBox<>(FXCollections.observableArrayList(DisasterType.values()));
    private final ComboBox<ResourceType> resource = new ComboBox<>(FXCollections.observableArrayList(ResourceType.values()));
    private final TextField quantity = new TextField();
    private final TextField people = new TextField();
    private final ComboBox<UrgencyLevel> urgency = new ComboBox<>(FXCollections.observableArrayList(UrgencyLevel.values()));
    private final ComboBox<String> city = new ComboBox<>(FXCollections.observableArrayList(CityDirectory.cityNames()));
    private final TextField phone = new TextField();
    private final CheckBox vulnerable = new CheckBox("Children / elderly / pregnant women present");
    private final Label scorePreview = new Label("Priority score: -");

    // filters
    private final TextField search = new TextField();
    private final ComboBox<String> statusFilter = new ComboBox<>();

    public RequestsView(CrisisService service, Toast toast) {
        this.service = service;
        this.toast = toast;
        root.setLeft(buildForm());
        root.setCenter(buildTable());
        root.setPadding(new Insets(22));
        root.getStyleClass().add("page");
    }

    private Node buildForm() {
        title.setPromptText("e.g. Flood relief camp needs food");
        quantity.setPromptText("units");
        people.setPromptText("headcount");
        phone.setPromptText("+91 ...");
        disaster.setPromptText("Select");
        resource.setPromptText("Select");
        urgency.setPromptText("Select");
        city.setPromptText("Select");
        disaster.setMaxWidth(Double.MAX_VALUE);
        resource.setMaxWidth(Double.MAX_VALUE);
        urgency.setMaxWidth(Double.MAX_VALUE);
        city.setMaxWidth(Double.MAX_VALUE);

        // numeric-only text fields (input validation as you type)
        Consumer<TextField> numeric = tf -> tf.textProperty().addListener((obs, o, n) -> {
            if (!n.matches("\\d*")) tf.setText(n.replaceAll("[^\\d]", ""));
        });
        numeric.accept(quantity);
        numeric.accept(people);

        // live priority preview: listeners on every input that affects the score
        Runnable preview = this::updateScorePreview;
        urgency.valueProperty().addListener((o, a, b) -> preview.run());
        people.textProperty().addListener((o, a, b) -> preview.run());
        vulnerable.selectedProperty().addListener((o, a, b) -> preview.run());
        scorePreview.getStyleClass().add("score-preview");

        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(10);
        int row = 0;
        g.addRow(row++, new Label("Title"), title);
        g.addRow(row++, new Label("Disaster"), disaster);
        g.addRow(row++, new Label("Resource needed"), resource);
        g.addRow(row++, new Label("Quantity"), quantity);
        g.addRow(row++, new Label("People affected"), people);
        g.addRow(row++, new Label("Urgency"), urgency);
        g.addRow(row++, new Label("City"), city);
        g.addRow(row++, new Label("Contact phone"), phone);
        g.add(vulnerable, 0, row++, 2, 1);
        GridPane.setHgrow(title, Priority.ALWAYS);

        Button submit = new Button("Raise SOS request");
        submit.getStyleClass().add("btn-danger");
        submit.setMaxWidth(Double.MAX_VALUE);
        submit.setOnAction(e -> submit());
        submit.setDefaultButton(true);

        Button clear = new Button("Clear");
        clear.setOnAction(e -> clearForm());

        VBox card = UiUtils.card("New emergency request", g, scorePreview, new HBox(10, submit, clear));
        HBox.setHgrow(submit, Priority.ALWAYS);
        card.setPrefWidth(350);
        BorderPane.setMargin(card, new Insets(0, 18, 0, 0));
        return card;
    }

    private void updateScorePreview() {
        if (urgency.getValue() == null) { scorePreview.setText("Priority score: -"); return; }
        EmergencyRequest tmp = new EmergencyRequest("tmp", DisasterType.OTHER, ResourceType.FOOD, 1,
                Validator.parseIntOr(people.getText(), 0), urgency.getValue(), CityDirectory.require("Patiala"));
        tmp.setVulnerableGroups(vulnerable.isSelected());
        scorePreview.setText(String.format("Priority score preview: %.1f", tmp.priorityScore()));
    }

    private Node buildTable() {
        table.getColumns().addAll(List.of(
                UiUtils.column("ID", EmergencyRequest::getId, 42),
                UiUtils.column("Title", EmergencyRequest::getTitle, 230),
                UiUtils.column("Need", (EmergencyRequest r) -> r.getResourceType().getLabel(), 115),
                UiUtils.progressColumn("Fulfilled", (EmergencyRequest r) -> r.fulfilmentPercent() / 100,
                        r -> r.getQuantityFulfilled() + " / " + r.getQuantityNeeded(), 105),
                UiUtils.urgencyColumn(EmergencyRequest::getUrgency),
                UiUtils.column("Priority", EmergencyRequest::priorityScore, 62),
                UiUtils.column("City", EmergencyRequest::getCity, 85),
                UiUtils.statusColumn(EmergencyRequest::getStatus),
                UiUtils.column("Source", EmergencyRequest::getSource, 75),
                UiUtils.column("Raised", (EmergencyRequest r) -> TimeUtils.ago(r.getCreatedAt()), 80)));
        SortedList<EmergencyRequest> sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
        table.setPlaceholder(new Label("No requests match the filter"));
        VBox.setVgrow(table, Priority.ALWAYS);

        search.setPromptText("Search title or city...");
        statusFilter.getItems().add("ALL");
        for (RequestStatus s : RequestStatus.values()) statusFilter.getItems().add(s.name());
        statusFilter.setValue("ALL");
        search.textProperty().addListener((o, a, b) -> applyFilter());
        statusFilter.valueProperty().addListener((o, a, b) -> applyFilter());

        Button cancel = new Button("Cancel selected");
        cancel.setOnAction(e -> cancelSelected());
        Button details = new Button("Dispatch details");
        details.setOnAction(e -> showDispatches());
        cancel.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        details.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());

        HBox toolbar = new HBox(10, search, statusFilter, UiUtils.hSpacer(), details, cancel);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(search, Priority.ALWAYS);
        return UiUtils.card("All emergency requests", toolbar, table);
    }

    private void applyFilter() {
        String q = search.getText() == null ? "" : search.getText().toLowerCase().trim();
        String st = statusFilter.getValue();
        filtered.setPredicate(r ->
                (q.isEmpty() || r.getTitle().toLowerCase().contains(q) || r.getCity().toLowerCase().contains(q))
                        && (st == null || "ALL".equals(st) || r.getStatus().name().equals(st)));
    }

    private void submit() {
        EmergencyRequest r = new EmergencyRequest(title.getText(), disaster.getValue(), resource.getValue(),
                Validator.parseIntOr(quantity.getText(), 0), Validator.parseIntOr(people.getText(), 0),
                urgency.getValue(), CityDirectory.find(city.getValue()).orElse(null));
        r.setContactPhone(phone.getText());
        r.setVulnerableGroups(vulnerable.isSelected());
        try {
            service.createRequest(r);
            toast.show("Request raised", r.getTitle() + " - priority " + r.priorityScore(), Toast.Kind.SUCCESS);
            clearForm();
            refresh();
        } catch (ValidationException ex) {
            UiUtils.validationError(ex);
        }
    }

    private void clearForm() {
        title.clear(); quantity.clear(); people.clear(); phone.clear();
        disaster.setValue(null); resource.setValue(null); urgency.setValue(null); city.setValue(null);
        vulnerable.setSelected(false);
    }

    private void cancelSelected() {
        EmergencyRequest r = table.getSelectionModel().getSelectedItem();
        if (r == null || !UiUtils.confirm("Cancel request?", r.getTitle())) return;
        try {
            service.cancelRequest(r.getId());
            toast.show("Cancelled", r.getTitle(), Toast.Kind.WARNING);
            refresh();
        } catch (ValidationException ex) {
            UiUtils.validationError(ex);
        } catch (CrisisConnectException ex) {
            UiUtils.error("Could not cancel", ex.getMessage());
        }
    }

    private void showDispatches() {
        EmergencyRequest r = table.getSelectionModel().getSelectedItem();
        if (r == null) return;
        List<Allocation> list = service.allocationsFor(r.getId());
        StringBuilder sb = new StringBuilder();
        if (list.isEmpty()) sb.append("Nothing dispatched yet. Run Smart Match from the Match Center.");
        for (Allocation a : list) {
            sb.append(String.format("• %d %s - %s (%s)%n   %.1f km away, ETA ~%d min%n", a.getQuantity(),
                    r.getResourceType().getUnit(), a.getResourceName(), a.getOrganizationName(),
                    a.getDistanceKm(), a.etaMinutes()));
        }
        UiUtils.info(r.getTitle(), sb.toString());
    }

    @Override public String title() { return "Emergency Requests"; }

    @Override public Node root() { return root; }

    @Override
    public void refresh() {
        data.setAll(service.requests());
    }
}
