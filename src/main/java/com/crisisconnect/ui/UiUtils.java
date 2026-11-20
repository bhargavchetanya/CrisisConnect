package com.crisisconnect.ui;

import com.crisisconnect.exception.ValidationException;
import com.crisisconnect.model.RequestStatus;
import com.crisisconnect.model.UrgencyLevel;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/** Re-usable JavaFX building blocks (cards, badges, dialogs, table columns). */
public final class UiUtils {

    private UiUtils() { }

    // ---------------------------------------------------------------- dialogs
    /** Dialogs have their own Scene, so the dark stylesheet must be attached separately. */
    public static <D extends Dialog<?>> D themed(D dialog) {
        java.net.URL css = UiUtils.class.getResource("/styles.css");
        if (css != null) dialog.getDialogPane().getStylesheets().add(css.toExternalForm());
        return dialog;
    }

    public static void error(String header, String content) {
        Alert a = new Alert(Alert.AlertType.ERROR, content, ButtonType.OK);
        a.setTitle("CrisisConnect");
        a.setHeaderText(header);
        themed(a).showAndWait();
    }

    public static void validationError(ValidationException e) {
        error("Please fix the following", "• " + String.join("\n• ", e.getErrors()));
    }

    public static void info(String header, String content) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, content, ButtonType.OK);
        a.setTitle("CrisisConnect");
        a.setHeaderText(header);
        themed(a).showAndWait();
    }

    public static boolean confirm(String header, String content) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, content, ButtonType.YES, ButtonType.NO);
        a.setTitle("Please confirm");
        a.setHeaderText(header);
        Optional<ButtonType> res = themed(a).showAndWait();
        return res.isPresent() && res.get() == ButtonType.YES;
    }

    // ---------------------------------------------------------------- layout helpers
    public static Region hSpacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    /** Dark rounded card with a title. */
    public static VBox card(String title, Node... content) {
        Label t = new Label(title);
        t.getStyleClass().add("card-title");
        VBox box = new VBox(12, t);
        box.getChildren().addAll(content);
        box.getStyleClass().add("card");
        return box;
    }

    /** KPI tile: big number + caption + coloured accent bar. */
    public static VBox kpi(Label value, String caption, String accentClass) {
        value.getStyleClass().add("kpi-value");
        Label cap = new Label(caption.toUpperCase());
        cap.getStyleClass().add("kpi-caption");
        VBox box = new VBox(4, value, cap);
        box.getStyleClass().addAll("kpi", accentClass);
        box.setMinWidth(150);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    public static Label urgencyBadge(UrgencyLevel u) {
        Label l = new Label(u.name());
        l.getStyleClass().addAll("badge", "badge-" + u.name().toLowerCase());
        return l;
    }

    public static Label statusBadge(RequestStatus s) {
        Label l = new Label(s.name().replace('_', ' '));
        l.getStyleClass().addAll("badge", "status-" + s.name().toLowerCase().replace('_', '-'));
        return l;
    }

    // ---------------------------------------------------------------- table columns (generic)
    /** Creates a column from a getter - type-safe alternative to PropertyValueFactory. */
    public static <T, V> TableColumn<T, V> column(String title, Function<T, V> getter, double width) {
        TableColumn<T, V> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(getter.apply(cd.getValue())));
        c.setPrefWidth(width);
        return c;
    }

    /** Column that renders an urgency badge. */
    public static <T> TableColumn<T, UrgencyLevel> urgencyColumn(Function<T, UrgencyLevel> getter) {
        TableColumn<T, UrgencyLevel> c = column("Urgency", getter, 100);
        c.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(UrgencyLevel item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                setGraphic(empty || item == null ? null : urgencyBadge(item));
            }
        });
        return c;
    }

    /** Column that renders a status badge. */
    public static <T> TableColumn<T, RequestStatus> statusColumn(Function<T, RequestStatus> getter) {
        TableColumn<T, RequestStatus> c = column("Status", getter, 150);
        c.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(RequestStatus item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                setGraphic(empty || item == null ? null : statusBadge(item));
            }
        });
        return c;
    }

    /** Column with a progress bar + label, e.g. stock level or fulfilment. */
    public static <T> TableColumn<T, T> progressColumn(String title, ToDoubleFunction<T> fraction,
                                                       Function<T, String> text, double width) {
        TableColumn<T, T> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue()));
        c.setCellFactory(col -> new TableCell<>() {
            private final ProgressBar bar = new ProgressBar();
            private final Label lbl = new Label();
            private final StackPane pane = new StackPane(bar, lbl);
            {
                bar.setMaxWidth(Double.MAX_VALUE);
                lbl.getStyleClass().add("bar-label");
                pane.setAlignment(Pos.CENTER);
            }

            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); return; }
                double f = Math.max(0, Math.min(1, fraction.applyAsDouble(item)));
                bar.setProgress(f);
                bar.getStyleClass().removeAll("bar-low", "bar-mid", "bar-high");
                bar.getStyleClass().add(f < 0.25 ? "bar-low" : f < 0.6 ? "bar-mid" : "bar-high");
                lbl.setText(text.apply(item));
                setGraphic(pane);
            }
        });
        return c;
    }
}
