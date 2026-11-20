package com.crisisconnect.ui.views;

import com.crisisconnect.AppContext;
import com.crisisconnect.exception.CrisisConnectException;
import com.crisisconnect.service.CsvImportService;
import com.crisisconnect.service.DataSnapshot;
import com.crisisconnect.ui.Toast;
import com.crisisconnect.ui.UiUtils;
import com.crisisconnect.ui.View;
import com.crisisconnect.util.TimeUtils;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/** File handling screen: reports, CSV export/import, serialization backup/restore. */
public class ReportsView implements View {

    private final AppContext ctx;
    private final Toast toast;
    private final TextArea preview = new TextArea();
    private final VBox root;

    public ReportsView(AppContext ctx, Toast toast) {
        this.ctx = ctx;
        this.toast = toast;

        preview.setEditable(false);
        preview.getStyleClass().add("console");
        VBox.setVgrow(preview, Priority.ALWAYS);

        FlowPane actions = new FlowPane(14, 14,
                tile("Situation report", "Text SITREP with gap analysis & low-stock alerts", "Generate", this::sitrep),
                tile("Export requests", "All requests as CSV (Excel friendly)", "Export CSV", this::exportRequests),
                tile("Export dispatches", "Dispatch history with distance & ETA", "Export CSV", this::exportDispatches),
                tile("Import field reports", "Bulk-load SOS requests from a CSV file", "Import CSV", this::importCsv),
                tile("Backup database", "Serialize everything to a .ser file", "Backup", this::backup),
                tile("Restore backup", "Deserialize a .ser file back into the DB", "Restore", this::restore));

        root = new VBox(16, UiUtils.card("Reports & data tools", actions), UiUtils.card("Output", preview));
        VBox.setVgrow(root.getChildren().get(1), Priority.ALWAYS);
        root.setPadding(new Insets(22));
        root.getStyleClass().add("page");
    }

    private VBox tile(String title, String desc, String button, Runnable action) {
        Label t = new Label(title);
        t.getStyleClass().add("tile-title");
        Label d = new Label(desc);
        d.getStyleClass().add("muted");
        d.setWrapText(true);
        Button b = new Button(button);
        b.getStyleClass().add("btn-primary");
        b.setOnAction(e -> action.run());
        VBox box = new VBox(8, t, d, new HBox(b));
        box.getStyleClass().add("tile");
        box.setPrefWidth(250);
        return box;
    }

    private Window window() { return root.getScene().getWindow(); }

    private void sitrep() {
        try {
            String text = ctx.reports().buildSituationReport();
            Path file = ctx.reports().saveSituationReport();
            preview.setText(text + "\nSaved to: " + file.toAbsolutePath());
            toast.show("SITREP generated", file.getFileName().toString(), Toast.Kind.SUCCESS);
        } catch (IOException e) {
            UiUtils.error("Could not write report", e.getMessage());
        }
    }

    private void exportRequests() {
        try {
            Path p = ctx.reports().exportRequestsCsv();
            preview.setText("Requests exported to\n" + p.toAbsolutePath());
            toast.show("CSV exported", p.getFileName().toString(), Toast.Kind.SUCCESS);
        } catch (IOException e) {
            UiUtils.error("Export failed", e.getMessage());
        }
    }

    private void exportDispatches() {
        try {
            Path p = ctx.reports().exportAllocationsCsv();
            preview.setText("Dispatches exported to\n" + p.toAbsolutePath());
            toast.show("CSV exported", p.getFileName().toString(), Toast.Kind.SUCCESS);
        } catch (IOException e) {
            UiUtils.error("Export failed", e.getMessage());
        }
    }

    private void importCsv() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Choose field report CSV");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        File samples = new File("samples");
        if (samples.isDirectory()) fc.setInitialDirectory(samples);
        File f = fc.showOpenDialog(window());
        if (f == null) return;
        try {
            CsvImportService.ImportResult res = ctx.csvImport().importRequests(f.toPath());
            StringBuilder sb = new StringBuilder("Imported " + res.imported() + " request(s) from " + f.getName() + "\n");
            if (!res.errors().isEmpty()) {
                sb.append("\nSkipped lines:\n");
                res.errors().forEach(err -> sb.append("  x ").append(err).append('\n'));
            }
            preview.setText(sb.toString());
            toast.show("Import finished", res.imported() + " imported, " + res.errors().size() + " skipped",
                    res.errors().isEmpty() ? Toast.Kind.SUCCESS : Toast.Kind.WARNING);
        } catch (IOException e) {
            UiUtils.error("Could not read file", e.getMessage());
        }
    }

    private void backup() {
        try {
            Path p = ctx.backups().backup();
            preview.setText("Snapshot serialized to\n" + p.toAbsolutePath()
                    + "\n\nCopy this file to a pen-drive to move the data to another control room.");
            toast.show("Backup complete", p.getFileName().toString(), Toast.Kind.SUCCESS);
        } catch (IOException e) {
            UiUtils.error("Backup failed", e.getMessage());
        }
    }

    private void restore() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Choose backup (.ser)");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CrisisConnect snapshot", "*.ser"));
        File dir = new File("backups");
        if (dir.isDirectory()) fc.setInitialDirectory(dir);
        File f = fc.showOpenDialog(window());
        if (f == null) return;
        try {
            DataSnapshot snap = ctx.backups().read(f.toPath());
            String info = "Snapshot taken " + TimeUtils.display(snap.getTakenAt()) + "\n" + snap.summary();
            if (!UiUtils.confirm("Replace ALL current data?", info)) return;
            ctx.backups().restore(snap);
            preview.setText("Restored from " + f.getName() + "\n" + info);
            toast.show("Restore complete", snap.summary(), Toast.Kind.SUCCESS);
        } catch (IOException | ClassNotFoundException e) {
            UiUtils.error("Not a valid backup file", e.getMessage());
        } catch (CrisisConnectException e) {
            UiUtils.error("Restore rolled back", e.getMessage());
        }
    }

    @Override public String title() { return "Reports & Data"; }

    @Override public Node root() { return root; }

    @Override public void refresh() { }
}
