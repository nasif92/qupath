package qupath.lib.gui.tools.PDL1;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.tools.PDL1.PDL1Export;
import qupath.lib.gui.tools.PDL1.PDL1Timer;
import qupath.lib.gui.tools.PDL1.PDL1Tools;
import qupath.lib.images.ImageData;


import java.awt.image.BufferedImage;


public class PDL1ScoringPane extends VBox {

    private final TextField cpsField = new TextField();
    private final Label denomLabel = new Label();
    private final Label nucleiLabel = new Label();
    private final VBox helperList = new VBox(4);



    private ImageData<BufferedImage> imageData;
    private boolean toolMode = true;

    public PDL1ScoringPane() {
        setSpacing(10);
        setPadding(new Insets(10));

        var title = new Label("PD-L1 Scoring");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        cpsField.setPromptText("Enter CPS (0–100)");

        var btnDone = new Button("Done");
        btnDone.setMaxWidth(Double.MAX_VALUE);
        btnDone.setOnAction(e -> onDone());

        var btnCancel = new Button("Cancel scoring");
        btnCancel.setMaxWidth(Double.MAX_VALUE);
        btnCancel.setOnAction(e -> onCancel());

        getChildren().addAll(
                title,
                denomLabel,
                nucleiLabel,
                new Separator(),
                new Label("CPS score:"),
                cpsField,
                btnDone,
                btnCancel,
                new Separator()

        );

        setToolMode(true);
    }

    public void setToolMode(boolean toolMode) {
        this.toolMode = toolMode;
        helperList.setManaged(toolMode);
        helperList.setVisible(toolMode);
    }

    public void bindTo(ImageData<BufferedImage> imageData) {
        this.imageData = imageData;
        // preload CPS if you store it in project metadata
        Float cps = PDL1Tools.readCpsFromProjectMetadata(imageData);
        cpsField.setText(cps == null ? "" : Float.toString(cps));
    }

    /** Called periodically from PDL1Tools on FX thread */
    public void updateCounts(int denomTumor, int nuclei) {
        denomLabel.setText("Denominator (Tumor): " + denomTumor);
        nucleiLabel.setText("Nuclei: " + nuclei);

        if (!toolMode) return;

        helperList.getChildren().clear();
        int[][] ranges = {
                {1, 5}, {6, 10}, {11, 20}, {21, 30}, {31, 40},
                {41, 50}, {51, 60}, {61, 70}, {71, 80}, {81, 90}
        };
        for (var r : ranges) {
            int pLow = r[0], pHigh = r[1];
            double cLow = denomTumor * pLow / 100.0;
            double cHigh = denomTumor * pHigh / 100.0;
            helperList.getChildren().add(new Label(
                    String.format("%d–%d%% (%.0f–%.0f)", pLow, pHigh, cLow, cHigh)
            ));
        }
    }

    private void onDone() {
        if (imageData == null)
            return;

        Float cpsInt = parseCpsOrNull();
        if (cpsInt == null)
            return;

        float cps = cpsInt;

        // 1️⃣ Stop timer (writes elapsed time into project metadata)
        PDL1Timer.stop(imageData);

        // 2️⃣ Save CPS score
        if (!PDL1Tools.writeCpsToProjectMetadata(imageData, cps))
            return;

        // 3️⃣ Export one row (reads CPS + elapsed time + counts)
        PDL1Export.appendRow(
                imageData,
                PDL1Tools.getCurrentUser(),
                cps,
                PDL1Tools.getLastDenomTumor(),
                PDL1Tools.getLastNuclei(),
                toolMode
        );

        // 4️⃣ End session UI + counter
        PDL1Tools.endScoringSession();
        showScoringCompleteDialog();

        // 6️⃣ Switch back to Project tab
        var gui = QuPathGUI.getInstance();
        if (gui != null) {
            gui.selectProjectTab();
        }

    }

    // just a dialog showing scoring completed
    private void showScoringCompleteDialog() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("PD-L1 Scoring");
        alert.setHeaderText(null);     // no header
        alert.setGraphic(null);        // no icon
        alert.setContentText("PD-L1 (CPS) scoring complete.");
        alert.showAndWait();
    }


    private void onCancel() {
        PDL1Tools.endScoringSession();
        // 6️⃣ Switch back to Project tab
        var gui = QuPathGUI.getInstance();
        if (gui != null) {
            gui.selectProjectTab();
        }
    }

    private Float parseCpsOrNull() {
        String txt = cpsField.getText() == null ? "" : cpsField.getText().trim();
        if (txt.isEmpty()) {
            Dialogs.showErrorMessage("PD-L1", "Please enter a CPS score (0–100).");
            return null;
        }
        float cps;
        try {
            cps = Float.parseFloat(txt);
        } catch (Exception e) {
            Dialogs.showErrorMessage("PD-L1", "CPS must be a number (0–100).");
            return null;
        }
        if (cps < 0 || cps > 100) {
            Dialogs.showErrorMessage("PD-L1", "CPS must be in the range 0–100.");
            return null;
        }
        return cps;
    }
}
