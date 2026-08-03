package qupath.lib.gui.tools;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javafx.concurrent.Task;
import javafx.scene.control.Alert;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;

import org.controlsfx.dialog.ProgressDialog;
import qupath.fx.dialogs.FileChoosers;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.images.ImageData;
import qupath.lib.io.PathIO;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AnnotationImportTool {

    private static final Logger logger = LoggerFactory.getLogger(AnnotationImportTool.class);

    private AnnotationImportTool() {}

    private static class ImportResult {
        final int done;
        final int skipped;
        final List<String> failures;
        ImportResult(int done, int skipped, List<String> failures) {
            this.done = done;
            this.skipped = skipped;
            this.failures = failures;
        }
    }

    public static void importAllProjectAnnotations(Project<BufferedImage> project, Stage owner) {
        if (project == null) {
            qupath.fx.dialogs.Dialogs.showInfoNotification("Import annotations", "No project is open.");
            return;
        }

        List<ProjectImageEntry<BufferedImage>> allEntries = project.getImageList();
        if (allEntries.isEmpty()) {
            Dialogs.showInfoNotification("Export Annotations", "Project has no images.");
            return;
        }

        List<ProjectImageEntry<BufferedImage>> entries =
                ImageSelectionTool.promptToSelectImages(QuPathGUI.getInstance(), allEntries);
        if (entries == null)
            return; // user cancelled the picker, or selected nothing

        File inputDir = FileChoosers.promptForDirectory("Select folder containing annotation GeoJSON files", null);
        if (inputDir == null)
            return;

        Task<ImportResult> task = createImportTask(entries, inputDir);

        ProgressDialog progressDialog = new ProgressDialog(task);
        progressDialog.setTitle("Import annotations");
        if (owner != null)
            progressDialog.initOwner(owner);

        task.setOnSucceeded(e -> {
            progressDialog.close();
            ImportResult result = task.getValue();
            reportResult(result.done, result.skipped, result.failures);
        });
        task.setOnFailed(e -> {
            progressDialog.close();
            qupath.fx.dialogs.Dialogs.showErrorNotification("Import annotations", "Task failed: " + task.getException());
        });
        task.setOnCancelled(e -> progressDialog.close());

        Thread thread = new Thread(task, "annotation-import-thread");
        thread.setDaemon(true);
        thread.start();
        progressDialog.showAndWait();
    }

    private static Task<ImportResult> createImportTask(List<ProjectImageEntry<BufferedImage>> entries, File inputDir) {
        return new Task<>() {
            @Override
            protected ImportResult call() {
                int total = entries.size();
                int done = 0;
                int skipped = 0;
                List<String> failures = new ArrayList<>();

                for (var entry : entries) {
                    if (isCancelled())
                        break;

                    updateMessage("Importing " + entry.getImageName() + " (" + (done + 1) + "/" + total + ")");

                    String safeName = GeneralTools.stripInvalidFilenameChars(entry.getImageName());
                    File geoJsonFile = new File(inputDir, safeName + ".geojson");

                    if (!geoJsonFile.exists()) {
                        skipped++;
                    } else {
                        try {
                            importEntryAnnotations(entry, geoJsonFile);
                        } catch (Exception ex) {
                            failures.add(entry.getImageName() + " — " + ex.getMessage());
                            logger.error("Failed to import annotations for {}", entry.getImageName(), ex);
                        }
                    }

                    done++;
                    updateProgress(done, total);
                }

                return new ImportResult(done, skipped, failures);
            }
        };
    }

    private static void importEntryAnnotations(ProjectImageEntry<BufferedImage> entry, File geoJsonFile) throws Exception {
        ImageData<BufferedImage> imageData = entry.readImageData();
        try {
            List<PathObject> objects = PathIO.readObjects(geoJsonFile);
            imageData.getHierarchy().addObjects(objects);
            entry.saveImageData(imageData);
        } finally {
            imageData.getServer().close();
        }
    }

    private static void reportResult(int done, int skipped, List<String> failures) {
        if (failures.isEmpty() && skipped == 0) {
            Dialogs.showInfoNotification("Import annotations",
                    "Imported annotations for " + done + " image(s).");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(done - skipped - failures.size()).append(" imported, ")
                .append(skipped).append(" skipped (no matching file), ")
                .append(failures.size()).append(" failed.\n\n");
        if (!failures.isEmpty()) {
            sb.append("Failed:\n");
            for (String f : failures)
                sb.append("• ").append(f).append("\n");
        }

        TextArea textArea = new TextArea(sb.toString());
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setPrefSize(480, 240);

        Alert alert = new Alert(failures.isEmpty() ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING);
        alert.setTitle("Import annotations — summary");
        alert.setHeaderText(null);
        alert.getDialogPane().setContent(textArea);
        alert.showAndWait();
    }
}