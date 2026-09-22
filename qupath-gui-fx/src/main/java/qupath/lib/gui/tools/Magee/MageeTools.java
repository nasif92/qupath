package qupath.lib.gui.tools.Magee;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MageeTools {

    private static final Logger logger = LoggerFactory.getLogger(MageeTools.class);

    // Patient columns are appended at the end so any consumer reading the
    // Magee columns by position is unaffected.
    private static final List<String> CSV_COLUMNS = List.of(
            "accession_id", "ER H-Score", "PR H-Score", "Ki67 %",
            "Nottingham Score", "Mitotic Score", "HER2 IHC", "HER2 SISH", "Tumor size",
            "Magee Eq 1", "Magee Eq 2", "Magee Eq 3", "Magee Decision",
            "Patient Name", "Patient DOB", "Patient Gender"
    );

    private static final List<String> GENDER_OPTIONS = List.of("Female", "Male", "Other", "Unknown");

    private static final int EARLIEST_BIRTH_YEAR = 1900;

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

        ScoreResult erResult = readScoreFile(mageeDir, "ER.txt");
        ScoreResult prResult = readScoreFile(mageeDir, "PR.txt");
        ScoreResult ki67Result = readScoreFile(mageeDir, "Ki67.txt");

        boolean anyFailed = erResult.state() == ScoreState.FAILED
                || prResult.state() == ScoreState.FAILED
                || ki67Result.state() == ScoreState.FAILED;

        StringBuilder missing = new StringBuilder();
        if (erResult.state() == ScoreState.MISSING) missing.append("ER.txt ");
        if (prResult.state() == ScoreState.MISSING) missing.append("PR.txt ");
        if (ki67Result.state() == ScoreState.MISSING) missing.append("Ki67.txt ");
        if (!missing.isEmpty()) {
            Dialogs.showInfoNotification("Magee Calculator",
                    "Could not read: " + missing + "— values will need to be entered manually.");
        }

        // --- check for an already-saved row and pre-fill from it ---
        var existingRow = readExistingCsvRow(mageeDir);

        // Patient info lives outside the Magee form; it's edited in its own dialog.
        AtomicReference<PatientInfo> patientInfo =
                new AtomicReference<>(PatientInfo.fromRow(existingRow));

        // --- build form fields ---
        TextField accessionField = new TextField(accessionId);
        accessionField.setEditable(false);
        styleReadOnly(accessionField);

        TextField erField = buildScoreField(erResult);
        TextField prField = buildScoreField(prResult);
        TextField ki67Field = buildScoreField(ki67Result);

        ComboBox<Integer> nottinghamBox = new ComboBox<>();
        nottinghamBox.getItems().addAll(3, 4, 5, 6, 7, 8, 9);
        ComboBox<Integer> mitoticBox = new ComboBox<>();
        mitoticBox.getItems().addAll(1, 2, 3);
        ComboBox<Integer> her2IhcBox = new ComboBox<>();
        her2IhcBox.getItems().addAll(0, 1, 2, 3);
        TextField her2SishField = new TextField();
        TextField tumorSizeField = new TextField();

        if (anyFailed) {
            nottinghamBox.setDisable(true);
            mitoticBox.setDisable(true);
            her2IhcBox.setDisable(true);
            her2SishField.setDisable(true);
            tumorSizeField.setDisable(true);
        } else if (existingRow != null) {
            // Pre-fill the manually-entered fields if a saved row exists
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

        if (anyFailed) {
            resultLabel.setText("FAILED");
            resultLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: red;");
        } else if (existingRow != null) {
            resultLabel.setText(String.format(
                    "Eq1: %s   Eq2: %s   Eq3: %s   →  %s",
                    existingRow.get("Magee Eq 1"), existingRow.get("Magee Eq 2"),
                    existingRow.get("Magee Eq 3"), existingRow.get("Magee Decision")));
            resultLabel.setStyle("-fx-font-weight: bold;");
        } else {
            resultLabel.setStyle("-fx-font-weight: bold;");
        }

        grid.add(resultLabel, 0, row++, 2, 1);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Magee Equation Calculator" + (existingRow != null ? " (existing data loaded)" : ""));
        dialog.getDialogPane().setContent(grid);

        ButtonType calcType = new ButtonType("Calculate", ButtonBar.ButtonData.APPLY);
        ButtonType patientType = new ButtonType("Enter Patient Info", ButtonBar.ButtonData.OTHER);
        ButtonType saveType = new ButtonType("Save to CSV", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(calcType, patientType, saveType, ButtonType.CANCEL);

        // Lay buttons out in workflow order (the order added above) instead of
        // the platform-specific ordering ButtonBar applies by default.
        ButtonBar buttonBar = (ButtonBar) dialog.getDialogPane().lookup(".button-bar");
        if (buttonBar != null)
            buttonBar.setButtonOrder(ButtonBar.BUTTON_ORDER_NONE);

        Button patientBtn = (Button) dialog.getDialogPane().lookupButton(patientType);
        updatePatientButton(patientBtn, patientInfo.get());

        if (anyFailed) {
            Node calcBtnNode = dialog.getDialogPane().lookupButton(calcType);
            Node saveBtnNode = dialog.getDialogPane().lookupButton(saveType);
            if (calcBtnNode != null) calcBtnNode.setDisable(true);
            if (saveBtnNode != null) saveBtnNode.setDisable(true);
            if (patientBtn != null) patientBtn.setDisable(true); // nothing can be saved anyway
        }

        if (qupath.getStage() != null)
            dialog.initOwner(qupath.getStage());

        Node calcBtn = dialog.getDialogPane().lookupButton(calcType);
        calcBtn.addEventFilter(ActionEvent.ACTION, e -> {
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

        // Opens the patient-info dialog on top of this one without closing it.
        patientBtn.addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            Window owner = dialog.getDialogPane().getScene().getWindow();
            showPatientInfoDialog(owner, patientInfo.get()).ifPresent(info -> {
                patientInfo.set(info);
                updatePatientButton(patientBtn, info);
            });
        });

        var choice = dialog.showAndWait();
        if (choice.isEmpty() || choice.get() != saveType)
            return;

        try {
            var vals = parseAndValidate(accessionField, erField, prField, ki67Field,
                    nottinghamBox, mitoticBox, her2IhcBox, her2SishField, tumorSizeField);
            var result = compute(vals);
            writePatientCsv(mageeDir, vals, result, patientInfo.get());
            Dialogs.showInfoNotification("Magee Calculator",
                    "Row for '" + vals.accessionId() + "' saved to magee.csv");
        } catch (IllegalArgumentException ex) {
            Dialogs.showErrorMessage("Invalid input", ex.getMessage());
        } catch (IOException ex) {
            logger.error("Failed to write magee.csv", ex);
            Dialogs.showErrorNotification("Magee Calculator", "Failed to save: " + ex.getMessage());
        }
    }

    // --- patient info ---

    /** Optional patient details. Not used by the Magee equations. Fields are never null. */
    private record PatientInfo(String name, String dob, String gender) {
        static final PatientInfo EMPTY = new PatientInfo("", "", "");

        boolean isEmpty() {
            return name.isBlank() && dob.isBlank() && gender.isBlank();
        }

        static PatientInfo fromRow(Map<String, String> row) {
            if (row == null)
                return EMPTY;
            return new PatientInfo(
                    cleanText(row.get("Patient Name")),
                    cleanText(row.get("Patient DOB")),
                    cleanText(row.get("Patient Gender")));
        }
    }

    private static void updatePatientButton(Button btn, PatientInfo info) {
        if (btn != null)
            btn.setText(info.isEmpty() ? "Enter Patient Info" : "Enter Patient Info");
    }

    /**
     * Shows a modal dialog (owned by the Magee dialog) for entering optional patient info.
     * Returns the new info on OK, or empty on Cancel (in which case nothing changes).
     */
    private static Optional<PatientInfo> showPatientInfoDialog(Window owner, PatientInfo current) {
        Dialog<PatientInfo> d = new Dialog<>();
        d.setTitle("Patient Information");
        d.setHeaderText("Enter Patient Info for Exporting to CSV");
        if (owner != null)
            d.initOwner(owner);

        TextField nameField = new TextField(current.name());
        nameField.setPromptText("Full name");

        // --- DOB as Year / Month / Day dropdowns ---
        int thisYear = LocalDate.now().getYear();
        ComboBox<Integer> yearBox = new ComboBox<>();
        for (int y = thisYear; y >= EARLIEST_BIRTH_YEAR; y--)
            yearBox.getItems().add(y);
        yearBox.setPromptText("Year");
        yearBox.setVisibleRowCount(12);

        ComboBox<Integer> monthBox = new ComboBox<>();
        for (int m = 1; m <= 12; m++)
            monthBox.getItems().add(m);
        monthBox.setPromptText("Month");
        monthBox.setVisibleRowCount(12);
        monthBox.setConverter(new StringConverter<>() {
            @Override public String toString(Integer m) {
                return m == null ? "" : Month.of(m).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            }
            @Override public Integer fromString(String s) { return null; } // not editable
        });

        ComboBox<Integer> dayBox = new ComboBox<>();
        dayBox.setPromptText("Day");
        dayBox.setVisibleRowCount(12);
        refreshDays(dayBox, null, null);

        // Keep the day list valid for the chosen month/year (e.g. no Feb 30).
        yearBox.valueProperty().addListener((obs, o, n) -> refreshDays(dayBox, n, monthBox.getValue()));
        monthBox.valueProperty().addListener((obs, o, n) -> refreshDays(dayBox, yearBox.getValue(), n));

        // Pre-fill from a previously saved DOB (yyyy-MM-dd)
        if (!current.dob().isBlank()) {
            try {
                LocalDate saved = LocalDate.parse(current.dob());
                if (!yearBox.getItems().contains(saved.getYear()))
                    yearBox.getItems().add(saved.getYear());
                yearBox.setValue(saved.getYear());
                monthBox.setValue(saved.getMonthValue());
                dayBox.setValue(saved.getDayOfMonth());
            } catch (DateTimeParseException ex) {
                logger.warn("Could not parse saved DOB '{}': {}", current.dob(), ex.getMessage());
            }
        }

        HBox dobBox = new HBox(6, yearBox, monthBox, dayBox);

        ComboBox<String> genderBox = new ComboBox<>();
        genderBox.getItems().addAll(GENDER_OPTIONS);
        genderBox.setPromptText("Select");
        if (!current.gender().isBlank()) {
            // Keep a previously saved value even if it isn't one of the standard options
            if (!genderBox.getItems().contains(current.gender()))
                genderBox.getItems().add(current.gender());
            genderBox.setValue(current.gender());
        }

        Button clearBtn = new Button("Clear all");
        clearBtn.setOnAction(e -> {
            nameField.clear();
            yearBox.setValue(null);
            monthBox.setValue(null);
            dayBox.setValue(null);
            genderBox.getSelectionModel().clearSelection();
            genderBox.setValue(null);
        });

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
        int row = 0;
        grid.addRow(row++, new Label("Patient Name"), nameField);
        grid.addRow(row++, new Label("Date of Birth"), dobBox);
        grid.addRow(row++, new Label("Gender"), genderBox);
        grid.add(clearBtn, 1, row++);

        d.getDialogPane().setContent(grid);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // DOB is optional: all three blank is fine, all three set is fine, partial is not.
        Node okBtn = d.getDialogPane().lookupButton(ButtonType.OK);
        okBtn.addEventFilter(ActionEvent.ACTION, e -> {
            Integer y = yearBox.getValue(), m = monthBox.getValue(), day = dayBox.getValue();
            int chosen = (y != null ? 1 : 0) + (m != null ? 1 : 0) + (day != null ? 1 : 0);
            if (chosen == 0)
                return;
            if (chosen < 3) {
                Dialogs.showErrorMessage("Incomplete date of birth",
                        "Please select a year, month and day — or leave all three blank.");
                e.consume();
                return;
            }
            if (LocalDate.of(y, m, day).isAfter(LocalDate.now())) {
                Dialogs.showErrorMessage("Invalid date of birth", "Date of birth cannot be in the future.");
                e.consume();
            }
        });

        d.setResultConverter(bt -> bt == ButtonType.OK
                ? new PatientInfo(
                cleanText(nameField.getText()),
                formatDob(yearBox.getValue(), monthBox.getValue(), dayBox.getValue()),
                cleanText(genderBox.getValue()))
                : null);

        return d.showAndWait();
    }

    /**
     * Rebuilds the day list (1..days in month) for the given year/month.
     * With no month chosen, shows 1-31. Clears the current day if it no longer fits.
     */
    private static void refreshDays(ComboBox<Integer> dayBox, Integer year, Integer month) {
        int maxDay = 31;
        if (month != null)
            maxDay = (year != null) ? YearMonth.of(year, month).lengthOfMonth()
                    : Month.of(month).maxLength(); // Feb -> 29 until a year is picked
        Integer selected = dayBox.getValue();
        dayBox.getItems().clear();
        for (int d = 1; d <= maxDay; d++)
            dayBox.getItems().add(d);
        dayBox.setValue(selected != null && selected <= maxDay ? selected : null);
    }

    /** Returns yyyy-MM-dd when all three parts are set, otherwise "". */
    private static String formatDob(Integer year, Integer month, Integer day) {
        if (year == null || month == null || day == null)
            return "";
        return LocalDate.of(year, month, day).toString();
    }

    /** Null-safe trim that also flattens any line breaks (which would break the CSV row). */
    private static String cleanText(String s) {
        if (s == null)
            return "";
        return s.replaceAll("[\\r\\n]+", " ").trim();
    }

    // --- form field helpers ---

    private static TextField buildScoreField(ScoreResult result) {
        TextField field = new TextField();
        switch (result.state()) {
            case VALID -> {
                field.setText(String.valueOf(result.value()));
                field.setEditable(false);
                styleReadOnly(field);
            }
            case FAILED -> {
                field.setText("FAILED");
                field.setEditable(false);
                styleFailed(field);
            }
            case MISSING -> {
                field.setText("");
                field.setEditable(true);
            }
        }
        return field;
    }

    private static void styleReadOnly(TextField field) {
        field.setStyle("-fx-control-inner-background: #d9d9d9; -fx-opacity: 1; -fx-text-fill: black;");
    }

    private static void styleFailed(TextField field) {
        field.setStyle("-fx-control-inner-background: #d9d9d9; -fx-opacity: 1; -fx-text-fill: red; -fx-font-weight: bold;");
    }

    // --- CSV I/O ---

    /**
     * Reads the single data row from an existing magee.csv, if present, as a
     * column-name -> value map. Returns null if the file doesn't exist or
     * can't be parsed.
     */
    private static Map<String, String> readExistingCsvRow(Path mageeDir) {
        File csvFile = mageeDir.resolve("magee.csv").toFile();
        if (!csvFile.isFile())
            return null;

        try {
            List<String> lines = Files.readAllLines(csvFile.toPath());
            if (lines.size() < 2)
                return null; // header only, or empty

            List<String> headers = parseCsvLine(lines.get(0));
            List<String> values = parseCsvLine(lines.get(1));

            if (headers.size() != values.size())
                return null;

            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++)
                row.put(headers.get(i).trim(), values.get(i).trim());
            return row;
        } catch (IOException ex) {
            logger.warn("Could not read existing magee.csv: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Splits one CSV line, honouring double-quoted fields (so "Doe, Jane" stays
     * one value) and keeping trailing empty fields (e.g. a blank gender).
     */
    private static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static void writePatientCsv(Path mageeDir, Inputs v, Result r, PatientInfo p) throws IOException {
        File csvFile = mageeDir.resolve("magee.csv").toFile();
        PatientInfo info = p == null ? PatientInfo.EMPTY : p;

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
                    escapeCsv(r.decision()),
                    escapeCsv(info.name()),
                    escapeCsv(info.dob()),
                    escapeCsv(info.gender())
            ));
        }
    }


    // --- score file reading ---
    private enum ScoreState { MISSING, FAILED, VALID }
    private record ScoreResult(Double value, ScoreState state) {}

    /**
     * Reads a score file. Returns:
     *   - VALID with the parsed value, for a normal number
     *   - FAILED, if the file's content is exactly -1 (no tumor nuclei found)
     *   - MISSING, if the file doesn't exist or can't be parsed at all
     */
    private static ScoreResult readScoreFile(Path mageeDir, String filename) {
        File exact = mageeDir.resolve(filename).toFile();
        File target = exact.isFile() ? exact : findCaseInsensitive(mageeDir, filename);

        if (target == null)
            return new ScoreResult(null, ScoreState.MISSING);

        try {
            String content = Files.readString(target.toPath()).trim();
            double value = Double.parseDouble(content);
            if (value == -1.0)
                return new ScoreResult(null, ScoreState.FAILED);
            return new ScoreResult(value, ScoreState.VALID);
        } catch (IOException | NumberFormatException ex) {
            logger.warn("Could not read score from {}: {}", target.getName(), ex.getMessage());
            return new ScoreResult(null, ScoreState.MISSING);
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


    private static String escapeCsv(String s) {
        if (s == null)
            return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r"))
            return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
}