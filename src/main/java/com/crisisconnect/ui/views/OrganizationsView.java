package com.crisisconnect.ui.views;

import com.crisisconnect.exception.ValidationException;
import com.crisisconnect.model.Organization;
import com.crisisconnect.model.OrganizationType;
import com.crisisconnect.service.CrisisService;
import com.crisisconnect.ui.Toast;
import com.crisisconnect.ui.UiUtils;
import com.crisisconnect.ui.View;
import com.crisisconnect.util.CityDirectory;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/** Partner organizations that own resources. */
public class OrganizationsView implements View {

    private final CrisisService service;
    private final Toast toast;
    private final TableView<Organization> table = new TableView<>();
    private final BorderPane root = new BorderPane();

    private final TextField name = new TextField();
    private final ComboBox<OrganizationType> type = new ComboBox<>(FXCollections.observableArrayList(OrganizationType.values()));
    private final ComboBox<String> city = new ComboBox<>(FXCollections.observableArrayList(CityDirectory.cityNames()));
    private final TextField phone = new TextField();
    private final TextField email = new TextField();

    public OrganizationsView(CrisisService service, Toast toast) {
        this.service = service;
        this.toast = toast;

        name.setPromptText("e.g. St. John Ambulance");
        phone.setPromptText("+91 ...");
        email.setPromptText("contact@org.in");
        type.setPromptText("Select");
        city.setPromptText("Select");
        type.setMaxWidth(Double.MAX_VALUE);
        city.setMaxWidth(Double.MAX_VALUE);

        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(10);
        g.addRow(0, new Label("Name"), name);
        g.addRow(1, new Label("Type"), type);
        g.addRow(2, new Label("City"), city);
        g.addRow(3, new Label("Phone"), phone);
        g.addRow(4, new Label("E-mail"), email);
        GridPane.setHgrow(name, Priority.ALWAYS);

        Button add = new Button("Register organization");
        add.getStyleClass().add("btn-primary");
        add.setMaxWidth(Double.MAX_VALUE);
        add.setOnAction(e -> add());

        VBox form = UiUtils.card("Join the network", g, add);
        form.setPrefWidth(360);
        BorderPane.setMargin(form, new Insets(0, 18, 0, 0));

        table.getColumns().addAll(List.of(
                UiUtils.column("ID", Organization::getId, 50),
                UiUtils.column("Name", Organization::getName, 240),
                UiUtils.column("Type", Organization::getType, 120),
                UiUtils.column("City", Organization::getCity, 110),
                UiUtils.column("Phone", Organization::getContactPhone, 140),
                UiUtils.column("E-mail", Organization::getEmail, 210)));
        VBox.setVgrow(table, Priority.ALWAYS);

        root.setLeft(form);
        root.setCenter(UiUtils.card("Partner organizations", table));
        root.setPadding(new Insets(22));
        root.getStyleClass().add("page");
    }

    private void add() {
        Organization o = new Organization();
        o.setName(name.getText());
        o.setType(type.getValue());
        o.setLocation(CityDirectory.find(city.getValue()).orElse(null));
        o.setContactPhone(phone.getText());
        o.setEmail(email.getText());
        try {
            service.registerOrganization(o);
            toast.show("Welcome aboard", o.getName() + " joined CrisisConnect", Toast.Kind.SUCCESS);
            name.clear(); phone.clear(); email.clear(); type.setValue(null); city.setValue(null);
            refresh();
        } catch (ValidationException ex) {
            UiUtils.validationError(ex);
        }
    }

    @Override public String title() { return "Organizations"; }

    @Override public Node root() { return root; }

    @Override
    public void refresh() {
        table.setItems(FXCollections.observableArrayList(service.organizations()));
    }
}
