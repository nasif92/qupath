package qupath.lib.gui.tools.Magee;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;

import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MageeTools {

    private static final Logger logger = LoggerFactory.getLogger(MageeTools.class);

    private static final List<String> CSV_COLUMNS = List.of(
            "accession_id", "ER H-Score", "PR H-Score", "Ki67 %",
            "Nottingham Score", "Mitotic Score", "HER2 IHC", "HER2 SISH", "Tumor size",
            "Magee Eq 1", "Magee Eq 2", "Magee Eq 3", "Magee Decision"
    );

    private MageeTools() {}


    public static void showMageeCalculator(QuPathGUI qupath) {
        var viewer = qupath.getViewer();
        var imageData = viewer == null ? null : viewer.getImageData();
        if (imageData == null) {
            Dialogs.showInfoNotification("Magee Calculator", "Open an image first.");
            return;
        }

        var project = qupath.getProject();
        if (project == null) {
            Dialogs.showInfoNotification("Magee Calculator", "No project is open.");
            return;
        }

        var entry = project.getEntry(imageData);
        if (entry == null) {
            Dialogs.showInfoNotification("Magee Calculator", "Current image is not part of the open project.");
            return;
        }

        String accessionId;
        Path mageeDir;
        try {
            accessionId = getAccessionId(project);
            mageeDir = getMageeDirectory(project);
        } catch (IOException ex) {
            Dialogs.showErrorNotification("Magee Calculator", ex.getMessage());
            return;
        }

        Double erScore = readScoreFile(mageeDir, "ER.txt");
        Double prScore = readScoreFile(mageeDir, "PR.txt");
        Double ki67Score = readScoreFile(mageeDir, "Ki67.txt");

        StringBuilder missing = new StringBuilder();
        if (erScore == null) missing.append("ER.txt ");
        if (prScore == null) missing.append("PR.txt ");
        if (ki67Score == null) missing.append("Ki67.txt ");
        if (!missing.isEmpty()) {
            Dialogs.showInfoNotification("Magee Calculator",
                    "Could not read: " + missing + "— values will need to be entered manually.");
        }

        // --- check for an already-saved row and pre-fill from it ---
        var existingRow = readExistingCsvRow(mageeDir);

        // --- build form fields ---
        TextField accessionField = new TextField(accessionId);
        accessionField.setEditable(false);
        accessionField.setStyle("-fx-opacity: 1; -fx-text-fill: black;");

        TextField erField = new TextField(erScore != null ? String.valueOf(erScore) : "");
        erField.setEditable(false);
        erField.setStyle("-fx-opacity: 1; -fx-text-fill: black;");

        TextField prField = new TextField(prScore != null ? String.valueOf(prScore) : "");
        prField.setEditable(false);
        prField.setStyle("-fx-opacity: 1; -fx-text-fill: black;");

        TextField ki67Field = new TextField(ki67Score != null ? String.valueOf(ki67Score) : "");
        ki67Field.setEditable(false);
        ki67Field.setStyle("-fx-opacity: 1; -fx-text-fill: black;");

        ComboBox<Integer> nottinghamBox = new ComboBox<>();
        nottinghamBox.getItems().addAll(3, 4, 5, 6, 7, 8, 9);
        ComboBox<Integer> mitoticBox = new ComboBox<>();
        mitoticBox.getItems().addAll(1, 2, 3);
        ComboBox<Integer> her2IhcBox = new ComboBox<>();
        her2IhcBox.getItems().addAll(0, 1, 2, 3);
        TextField her2SishField = new TextField();
        TextField tumorSizeField = new TextField();

        // Pre-fill the manually-entered fields if a saved row exists
        if (existingRow != null) {
            try {
                nottinghamBox.setValue(Integer.parseInt(existingRow.get("Nottingham Score")));
                mitoticBox.setValue(Integer.parseInt(existingRow.get("Mitotic Score")));
                her2IhcBox.setValue(Integer.parseInt(existingRow.get("HER2 IHC")));
                her2SishField.setText(existingRow.get("HER2 SISH"));
                tumorSizeField.setText(existingRow.get("Tumor size"));
            } catch (NumberFormatException | NullPointerException ex) {
                logger.warn("Could not parse existing magee.csv row for pre-fill: {}", ex.getMessage());
            }
        }

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));

        int row = 0;
        grid.addRow(row++, new Label("Accession ID"), accessionField);
        grid.addRow(row++, new Label("ER H-Score (0-300)"), erField);
        grid.addRow(row++, new Label("PR H-Score (0-300)"), prField);
        grid.addRow(row++, new Label("Ki67 % (0-100)"), ki67Field);
        grid.addRow(row++, new Label("Nottingham Score (3-9)"), nottinghamBox);
        grid.addRow(row++, new Label("Mitotic Score (1-3)"), mitoticBox);
        grid.addRow(row++, new Label("HER2 IHC (0-3)"), her2IhcBox);
        grid.addRow(row++, new Label("HER2 SISH"), her2SishField);
        grid.addRow(row++, new Label("Tumor size (mm)"), tumorSizeField);

        Label resultLabel = new Label();
        resultLabel.setWrapText(true);
        resultLabel.setStyle("-fx-font-weight: bold;");

        // If a saved row exists, show its previously-computed result immediately
        if (existingRow != null) {
            resultLabel.setText(String.format(
                    "Eq1: %s   Eq2: %s   Eq3: %s   →  %s",
                    existingRow.get("Magee Eq 1"), existingRow.get("Magee Eq 2"),
                    existingRow.get("Magee Eq 3"), existingRow.get("Magee Decision")));
        }

        grid.add(resultLabel, 0, row++, 2, 1);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Magee Equation Calculator" + (existingRow != null ? " (existing data loaded)" : ""));
        dialog.getDialogPane().setContent(grid);

        ButtonType calcType = new ButtonType("Calculate", ButtonBar.ButtonData.APPLY);
        ButtonType saveType = new ButtonType("Save to magee.csv", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(calcType, saveType, ButtonType.CANCEL);

        if (qupath.getStage() != null)
            dialog.initOwner(qupath.getStage());

        Node calcBtn = dialog.getDialogPane().lookupButton(calcType);
        calcBtn.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            try {
                var vals = parseAndValidate(accessionField, erField, prField, ki67Field,
                        nottinghamBox, mitoticBox, her2IhcBox, her2SishField, tumorSizeField);
                var result = compute(vals);
                resultLabel.setText(String.format(
                        "Eq1: %s   Eq2: %s   Eq3: %s   →  %s",
                        result.me1(), result.me2(), result.me3(), result.decision()));
            } catch (IllegalArgumentException ex) {
                Dialogs.showErrorMessage("Invalid input", ex.getMessage());
            }
            e.consume();
        });

        var choice = dialog.showAndWait();
        if (choice.isEmpty() || choice.get() != saveType)
            return;

        try {
            var vals = parseAndValidate(accessionField, erField, prField, ki67Field,
                    nottinghamBox, mitoticBox, her2IhcBox, her2SishField, tumorSizeField);
            var result = compute(vals);
            writePatientCsv(mageeDir, vals, result);
            Dialogs.showInfoNotification("Magee Calculator",
                    "Row for '" + vals.accessionId() + "' saved to magee.csv");
        } catch (IllegalArgumentException ex) {
            Dialogs.showErrorMessage("Invalid input", ex.getMessage());
        } catch (IOException ex) {
            logger.error("Failed to write magee.csv", ex);
            Dialogs.showErrorNotification("Magee Calculator", "Failed to save: " + ex.getMessage());
        }
    }

    /**
     * Reads the single data row from an existing magee.csv, if present, as a
     * column-name -> value map. Returns null if the file doesn't exist or
     * can't be parsed.
     */
    private static java.util.Map<String, String> readExistingCsvRow(Path mageeDir) {
        File csvFile = mageeDir.resolve("magee.csv").toFile();
        if (!csvFile.isFile())
            return null;

        try {
            List<String> lines = Files.readAllLines(csvFile.toPath());
            if (lines.size() < 2)
                return null; // header only, or empty

            String[] headers = lines.get(0).split(",");
            String[] values = lines.get(1).split(",");

            if (headers.length != values.length)
                return null;

            java.util.Map<String, String> row = new java.util.LinkedHashMap<>();
            for (int i = 0; i < headers.length; i++)
                row.put(headers[i].trim(), values[i].trim());
            return row;
        } catch (IOException ex) {
            logger.warn("Could not read existing magee.csv: {}", ex.getMessage());
            return null;
        }
    }

    private static void writePatientCsv(Path mageeDir, Inputs v, Result r) throws IOException {
        File csvFile = mageeDir.resolve("magee.csv").toFile();

        try (PrintWriter writer = new PrintWriter(new FileWriter(csvFile, false))) { // false = overwrite, single row
            writer.println(String.join(",", CSV_COLUMNS));
            writer.println(String.join(",",
                    escapeCsv(v.accessionId()),
                    String.valueOf(v.er()),
                    String.valueOf(v.pr()),
                    String.valueOf(v.ki67()),
                    String.valueOf(v.nottingham()),
                    String.valueOf(v.mitotic()),
                    String.valueOf(v.her2Ihc()),
                    String.valueOf(v.her2Sish()),
                    String.valueOf(v.tumorSize()),
                    String.valueOf(r.me1()),
                    String.valueOf(r.me2()),
                    String.valueOf(r.me3()),
                    escapeCsv(r.decision())
            ));
        }
    }
    // --- score file reading ---

    /**
     * Reads a single floating-point number from a text file. Returns null
     * if the file doesn't exist or its content isn't parseable as a number.
     */
    private static Double readScoreFile(Path mageeDir, String filename) {
        File exact = mageeDir.resolve(filename).toFile();
        File target = exact.isFile() ? exact : findCaseInsensitive(mageeDir, filename);

        if (target == null)
            return null;

        try {
            String content = Files.readString(target.toPath()).trim();
            return Double.parseDouble(content);
        } catch (IOException | NumberFormatException ex) {
            logger.warn("Could not read score from {}: {}", target.getName(), ex.getMessage());
            return null;
        }
    }

    private static File findCaseInsensitive(Path dir, String targetName) {
        File[] candidates = dir.toFile().listFiles((d, name) -> name.equalsIgnoreCase(targetName));
        return (candidates != null && candidates.length > 0) ? candidates[0] : null;
    }

    /**
     * Accession ID = the name of the folder containing the QuPath project
     * folder. The user never types this — it's derived automatically.
     */
    private static String getAccessionId(Project<?> project) throws IOException {
        Path path = project.getPath();
        if (path == null)
            throw new IOException("Project has no local file path (not stored on local filesystem)");

        Path projectFolder = Files.isDirectory(path) ? path : path.getParent();
        Path accessionFolder = projectFolder.getParent();

        if (accessionFolder == null)
            throw new IOException("Could not determine accession ID — project folder has no parent directory.");

        return accessionFolder.getFileName().toString();
    }

    private static Path getMageeDirectory(Project<?> project) throws IOException {
        Path path = project.getPath();
        if (path == null)
            throw new IOException("Project has no local file path (not stored on local filesystem)");
        Path baseDir = Files.isDirectory(path) ? path : path.getParent();
        Path mageeDir = baseDir.resolve("magee"); // flat, no accession subfolder
        Files.createDirectories(mageeDir);
        return mageeDir;
    }

    // --- validation ---

    private record Inputs(String accessionId, double er, double pr, double ki67,
                          int nottingham, int mitotic, int her2Ihc, double her2Sish, double tumorSize) {}

    private record Result(double me1, double me2, double me3, String decision) {}

    private static Inputs parseAndValidate(TextField accessionField, TextField erField, TextField prField,
                                           TextField ki67Field, ComboBox<Integer> nottinghamBox,
                                           ComboBox<Integer> mitoticBox, ComboBox<Integer> her2IhcBox,
                                           TextField her2SishField, TextField tumorSizeField) {
        String accessionId = accessionField.getText() == null ? "" : accessionField.getText().trim();
        if (accessionId.isEmpty())
            throw new IllegalArgumentException("Accession ID is required.");

        double er = parseFloat(erField.getText(), "ER H-Score", 0.0, 300.0);
        double pr = parseFloat(prField.getText(), "PR H-Score", 0.0, 300.0);
        double ki67 = parseFloat(ki67Field.getText(), "Ki67 %", 0.0, 100.0);

        Integer nottingham = nottinghamBox.getValue();
        if (nottingham == null)
            throw new IllegalArgumentException("Nottingham Score is required.");

        Integer mitotic = mitoticBox.getValue();
        if (mitotic == null)
            throw new IllegalArgumentException("Mitotic Score is required.");

        Integer her2Ihc = her2IhcBox.getValue();
        if (her2Ihc == null)
            throw new IllegalArgumentException("HER2 IHC is required.");

        double her2Sish = parseFloat(her2SishField.getText(), "HER2 SISH", null, null);
        double tumorSize = parseFloat(tumorSizeField.getText(), "Tumor size", null, null);

        return new Inputs(accessionId, er, pr, ki67, nottingham, mitotic, her2Ihc, her2Sish, tumorSize);
    }

    private static double parseFloat(String raw, String label, Double lo, Double hi) {
        if (raw == null || raw.isBlank())
            throw new IllegalArgumentException(label + " is required.");
        double value;
        try {
            value = Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + " must be a number.");
        }
        if (Double.isNaN(value))
            throw new IllegalArgumentException(label + " must be a valid number.");
        if (lo != null && value < lo)
            throw new IllegalArgumentException(label + " must be >= " + lo);
        if (hi != null && value > hi)
            throw new IllegalArgumentException(label + " must be <= " + hi);
        return value;
    }

    private static Result compute(Inputs v) {
        double me1 = MageeEquations.equation1(v.nottingham(), v.er(), v.pr(), v.her2Ihc(), v.her2Sish(), v.tumorSize(), v.ki67());
        double me2 = MageeEquations.equation2(v.nottingham(), v.er(), v.pr(), v.her2Ihc(), v.her2Sish(), v.tumorSize());
        double me3 = MageeEquations.equation3(v.er(), v.pr(), v.her2Ihc(), v.her2Sish(), v.ki67());
        String decision = MageeEquations.decision(me1, me2, me3, v.mitotic());
        return new Result(me1, me2, me3, decision);
    }

    // --- CSV output ---

    private static void appendToCsv(Path mageeDir, Inputs v, Result r) throws IOException {
        File csvFile = mageeDir.resolve("magee.csv").toFile();
        boolean writeHeader = !csvFile.exists();

        try (PrintWriter writer = new PrintWriter(new FileWriter(csvFile, true))) {
            if (writeHeader)
                writer.println(String.join(",", CSV_COLUMNS));

            writer.println(String.join(",",
                    escapeCsv(v.accessionId()),
                    String.valueOf(v.er()),
                    String.valueOf(v.pr()),
                    String.valueOf(v.ki67()),
                    String.valueOf(v.nottingham()),
                    String.valueOf(v.mitotic()),
                    String.valueOf(v.her2Ihc()),
                    String.valueOf(v.her2Sish()),
                    String.valueOf(v.tumorSize()),
                    String.valueOf(r.me1()),
                    String.valueOf(r.me2()),
                    String.valueOf(r.me3()),
                    escapeCsv(r.decision())
            ));
        }
    }

    private static String escapeCsv(String s) {
        if (s.contains(",") || s.contains("\""))
            return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
}