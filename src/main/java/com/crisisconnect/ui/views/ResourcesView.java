package com.crisisconnect.ui.views;

import com.crisisconnect.exception.CrisisConnectException;
import com.crisisconnect.exception.ValidationException;
import com.crisisconnect.model.Organization;
import com.crisisconnect.model.Resource;
import com.crisisconnect.model.ResourceFactory;
import com.crisisconnect.model.ResourceType;
import com.crisisconnect.service.CrisisService;
import com.crisisconnect.ui.Toast;
import com.crisisconnect.ui.UiUtils;
import com.crisisconnect.ui.View;
import com.crisisconnect.util.CityDirectory;
import com.crisisconnect.util.Validator;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Register resources (food, medical, shelter, vehicles, volunteers) and manage stock. */
public class ResourcesView implements View {

    private final CrisisService service;
    private final Toast toast;
    private final ObservableList<Resource> data = FXCollections.observableArrayList();
    private final FilteredList<Resource> filtered = new FilteredList<>(data, r -> true);
    private final TableView<Resource> table = new TableView<>();
    private final BorderPane root = new BorderPane();

    private final ComboBox<Organization> org = new ComboBox<>();
    private final ComboBox<ResourceType> type = new ComboBox<>(FXCollections.observableArrayList(ResourceType.values()));
    private final TextField name = new TextField();
    private final TextField qty = new TextField();
    private final ComboBox<String> city = new ComboBox<>(FXCollections.observableArrayList(CityDirectory.cityNames()));
    private final DatePicker expiry = new DatePicker();
    private final Label unitHint = new Label();
    private final ComboBox<Object> typeFilter = new ComboBox<>();
    private final TextField search = new TextField();

    public ResourcesView(CrisisService service, Toast toast) {
        this.service = service;
        this.toast = toast;
        root.setLeft(buildForm());
        root.setCenter(buildTable());
        root.setPadding(new Insets(22));
        root.getStyleClass().add("page");
    }

    private Node buildForm() {
        org.setPromptText("Select organization");
        type.setPromptText("Select type");
        city.setPromptText("Select city");
        name.setPromptText("e.g. Oxygen cylinders");
        qty.setPromptText("units");
        expiry.setPromptText("optional");
        for (ComboBox<?> c : List.of(org, type, city)) c.setMaxWidth(Double.MAX_VALUE);
        expiry.setMaxWidth(Double.MAX_VALUE);
        qty.textProperty().addListener((o, a, n) -> { if (!n.matches("\\d*")) qty.setText(n.replaceAll("\\D", "")); });

        // when the type changes show its unit; when an org is picked default to its city
        type.valueProperty().addListener((o, a, t) -> unitHint.setText(t == null ? "" : "Measured in " + t.getUnit()));
        org.valueProperty().addListener((o, a, og) -> { if (og != null && city.getValue() == null) city.setValue(og.getCity()); });
        unitHint.getStyleClass().add("muted");

        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(10);
        int row = 0;
        g.addRow(row++, new Label("Organization"), org);
        g.addRow(row++, new Label("Type"), type);
        g.addRow(row++, new Label("Name"), name);
        g.addRow(row++, new Label("Quantity"), qty);
        g.add(unitHint, 1, row++);
        g.addRow(row++, new Label("City"), city);
        g.addRow(row++, new Label("Expiry date"), expiry);
        GridPane.setHgrow(name, Priority.ALWAYS);

        Button add = new Button("Register resource");
        add.getStyleClass().add("btn-primary");
        add.setMaxWidth(Double.MAX_VALUE);
        add.setOnAction(e -> add());

        VBox card = UiUtils.card("Register a resource", g, add);
        card.setPrefWidth(330);
        BorderPane.setMargin(card, new Insets(0, 18, 0, 0));
        return card;
    }

    private Node buildTable() {
        table.getColumns().addAll(List.of(
                UiUtils.column("ID", Resource::getId, 42),
                UiUtils.column("Name", Resource::getName, 190),
                UiUtils.column("Type", (Resource r) -> r.getType().getLabel(), 115),
                UiUtils.column("Organization", Resource::getOrganizationName, 170),
                UiUtils.progressColumn("Stock left", (Resource r) -> 1 - r.utilization(),
                        r -> r.getAvailableQuantity() + " / " + r.getTotalCapacity(), 120),
                UiUtils.column("City", Resource::getCity, 90),
                UiUtils.column("Expiry", (Resource r) -> r.getExpiryDate().map(LocalDate::toString).orElse("—"), 100)));
        SortedList<Resource> sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
        VBox.setVgrow(table, Priority.ALWAYS);

        typeFilter.getItems().add("All types");
        typeFilter.getItems().addAll((Object[]) ResourceType.values());
        typeFilter.setValue("All types");
        search.setPromptText("Search name, organization, city...");
        typeFilter.valueProperty().addListener((o, a, b) -> applyFilter());
        search.textProperty().addListener((o, a, b) -> applyFilter());

        Button restock = new Button("Restock");
        restock.setOnAction(e -> restock());
        Button delete = new Button("Delete");
        delete.getStyleClass().add("btn-outline-danger");
        delete.setOnAction(e -> delete());
        restock.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        delete.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());

        HBox bar = new HBox(10, search, typeFilter, UiUtils.hSpacer(), restock, delete);
        bar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(search, Priority.ALWAYS);
        return UiUtils.card("Resource inventory", bar, table);
    }

    private void applyFilter() {
        Object t = typeFilter.getValue();
        String q = search.getText() == null ? "" : search.getText().toLowerCase().trim();
        filtered.setPredicate(r -> (!(t instanceof ResourceType rt) || r.getType() == rt)
                && (q.isEmpty() || r.getName().toLowerCase().contains(q)
                || String.valueOf(r.getOrganizationName()).toLowerCase().contains(q)
                || r.getCity().toLowerCase().contains(q)));
    }

    private void add() {
        if (type.getValue() == null || org.getValue() == null) {
            UiUtils.error("Missing information", "Please choose an organization and a resource type.");
            return;
        }
        Resource r = ResourceFactory.create(type.getValue(), org.getValue().getId(), name.getText(),
                Validator.parseIntOr(qty.getText(), 0), CityDirectory.find(city.getValue()).orElse(null));
        r.setExpiryDate(expiry.getValue());
        if (expiry.getValue() != null && expiry.getValue().isBefore(LocalDate.now())) {
            UiUtils.error("Invalid expiry", "Expiry date cannot be in the past.");
            return;
        }
        try {
            service.addResource(r);
            toast.show("Resource registered", r.getTotalCapacity() + " " + r.getUnit() + " • " + r.getName(), Toast.Kind.SUCCESS);
            name.clear(); qty.clear(); expiry.setValue(null);
            refresh();
        } catch (ValidationException ex) {
            UiUtils.validationError(ex);
        }
    }

    private void restock() {
        Resource r = table.getSelectionModel().getSelectedItem();
        TextInputDialog d = new TextInputDialog("50");
        d.setTitle("Restock");
        d.setHeaderText("Add units to: " + r.getName());
        d.setContentText("Extra " + r.getUnit() + ":");
        Optional<String> res = UiUtils.themed(d).showAndWait();
        res.ifPresent(txt -> {
            try {
                service.restock(r.getId(), Validator.parseIntOr(txt, 0));
                toast.show("Restocked", r.getName(), Toast.Kind.SUCCESS);
                refresh();
            } catch (ValidationException ex) {
                UiUtils.validationError(ex);
            } catch (CrisisConnectException ex) {
                UiUtils.error("Restock failed", ex.getMessage());
            }
        });
    }

    private void delete() {
        Resource r = table.getSelectionModel().getSelectedItem();
        if (!UiUtils.confirm("Delete resource?", r.getName() + "\nIts dispatch history will also be removed.")) return;
        try {
            service.deleteResource(r.getId());
            toast.show("Deleted", r.getName(), Toast.Kind.WARNING);
            refresh();
        } catch (CrisisConnectException ex) {
            UiUtils.error("Delete failed", ex.getMessage());
        }
    }

    @Override public String title() { return "Resource Inventory"; }

    @Override public Node root() { return root; }

    @Override
    public void refresh() {
        Organization selected = org.getValue();
        org.setItems(FXCollections.observableArrayList(service.organizations()));
        org.setValue(selected);
        data.setAll(service.resources());
    }
}
