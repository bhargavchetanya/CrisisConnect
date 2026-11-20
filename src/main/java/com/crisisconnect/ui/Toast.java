package com.crisisconnect.ui;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** Non-blocking notification that slides in at the bottom-right corner and fades away. */
public final class Toast {

    public enum Kind { INFO, SUCCESS, WARNING, ALERT }

    private final VBox container = new VBox(8);

    public Toast(StackPane host) {
        container.setAlignment(Pos.BOTTOM_RIGHT);
        container.setPickOnBounds(false);
        container.setMouseTransparent(true);
        container.setPadding(new Insets(20));
        container.setMaxSize(420, Double.MAX_VALUE);
        StackPane.setAlignment(container, Pos.BOTTOM_RIGHT);
        host.getChildren().add(container);
    }

    public void show(String title, String message, Kind kind) {
        Label t = new Label(title);
        t.getStyleClass().add("toast-title");
        Label m = new Label(message);
        m.setWrapText(true);
        m.getStyleClass().add("toast-msg");
        VBox box = new VBox(2, t, m);
        box.getStyleClass().addAll("toast", "toast-" + kind.name().toLowerCase());
        box.setMaxWidth(380);
        container.getChildren().add(box);

        box.setOpacity(0);
        box.setTranslateX(60);
        FadeTransition in = new FadeTransition(Duration.millis(250), box);
        in.setToValue(1);
        TranslateTransition slide = new TranslateTransition(Duration.millis(250), box);
        slide.setToX(0);
        PauseTransition stay = new PauseTransition(Duration.seconds(kind == Kind.ALERT ? 6 : 3.5));
        FadeTransition out = new FadeTransition(Duration.millis(400), box);
        out.setToValue(0);
        SequentialTransition seq = new SequentialTransition(in, stay, out);
        seq.setOnFinished(e -> container.getChildren().remove(box));
        slide.play();
        seq.play();
    }
}
