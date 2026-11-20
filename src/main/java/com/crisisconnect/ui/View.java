package com.crisisconnect.ui;

import javafx.scene.Node;

/** Every screen in the dashboard implements this small contract. */
public interface View {

    /** Title shown in the header. */
    String title();

    /** Root node placed in the centre of the BorderPane. */
    Node root();

    /** Reload data from the service layer. Called when the view is shown or data changes. */
    void refresh();
}
