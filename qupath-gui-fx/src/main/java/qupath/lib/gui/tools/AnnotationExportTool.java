package qupath.lib.gui.tools;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import qupath.fx.dialogs.Dialogs;
import javafx.concurrent.Task;
import javafx.stage.Stage;

import org.controlsfx.dialog.ProgressDialog;
import qupath.fx.dialogs.FileChoosers;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.io.PathIO;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper methods for batch-exporting annotations from every image
 * in a project as separate GeoJSON files.
 */
public class AnnotationExportTool {

    private static final Logger logger = LoggerFactory.getLogger(AnnotationExportTool.class);

    private AnnotationExportTool() {
        // static helper class, no instances
    }

    /**
     * Prompt the user for an output directory, then export annotations
     * for every image in the given project as GeoJSON, showing progress
     * in a dialog owned by the given stage.
     *
     * @param project the project whose images should be exported
     * @param owner   stage to own the progress dialog (maybe null)
     */

    public static void exportAllProjectAnnotations(Project<BufferedImage> project, Stage owner) {
        if (project == null) {
            Dialogs.showInfoNotification("Export Annotations", "No project is open.");
            return;
        }

        List<ProjectImageEntry<BufferedImage>> allEntries = project.getImageList();
        if (allEntries.isEmpty()) {
            Dialogs.showInfoNotification("Export Annotations", "Project has no images.");
            return;
        }

        // --- Image selection picker, same as "Run for project" ---
        List<ProjectImageEntry<BufferedImage>> entries =
                ImageSelectionTool.promptToSelectImages(QuPathGUI.getInstance(), allEntries);
        if (entries == null || entries.isEmpty()) {
            return; // user cancelled, or selected nothing
        }
        // --- end picker ---

        File outputDir = FileChoosers.promptForDirectory("Select output folder for annotation GeoJSON files", null);
        if (outputDir == null)
            return;

        File[] existing = outputDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".geojson"));
        int existingCount = existing == null ? 0 : existing.length;
        if (existingCount > 0) {
            boolean proceed = Dialogs.showConfirmDialog(
                    "Export Annotations",
                    outputDir.getName() + " already contains " + existingCount +
                            " .geojson file(s). Exporting will overwrite any with matching names. Continue?");
            if (!proceed)
                return;
        }

        Task<BatchResult> task = createExportTask(entries, outputDir); // <-- uses the PICKED entries, not allEntries

        ProgressDialog progressDialog = new ProgressDialog(task);
        progressDialog.setTitle("Export Annotations");
        if (owner != null)
            progressDialog.initOwner(owner);

        task.setOnSucceeded(e -> {
            BatchResult result = task.getValue();
            if (!result.failures.isEmpty())
                showSummaryDialog(result.done, result.failures, outputDir);
        });
        task.setOnFailed(e ->
                Dialogs.showErrorNotification("Export annotations", "Task failed: " + task.getException()));

        Thread thread = new Thread(task, "annotation-export-thread");
        thread.setDaemon(true);
        thread.start();
        progressDialog.showAndWait();
    }

    private static Task<BatchResult> createExportTask(List<ProjectImageEntry<BufferedImage>> entries, File outputDir) {
        return new Task<>() {
            @Override
            protected BatchResult call() {
                int total = entries.size();
                int done = 0;
                List<String> failures = new ArrayList<>();

                for (var entry : entries) {
                    if (isCancelled())
                        break;

                    updateMessage("Exporting " + entry.getImageName() + " (" + (done + 1) + "/" + total + ")");

                    try {
                        exportEntryAnnotations(entry, outputDir);
                    } catch (Exception ex) {
                        failures.add(entry.getImageName() + " — " + describeFailure(ex));
                        logger.error("Failed to export annotations for {}", entry.getImageName(), ex);
                    }

                    done++;
                    updateProgress(done, total);
                }

                // Show a brief "Done" message before the dialog auto-closes
                updateMessage(failures.isEmpty() ? "Done" : "Done — " + failures.size() + " failed");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }

                return new BatchResult(done, failures);
            }
        };
    }
    private static void showSummaryDialog(int done, List<String> failures, File outputDir) {
        if (failures.isEmpty()) {
            Dialogs.showInfoNotification("Export annotations",
                    "Exported annotations for " + done + " image(s) to " + outputDir.getName());
            return;
        }

        int succeeded = done - failures.size();
        StringBuilder sb = new StringBuilder();
        sb.append(succeeded).append(" of ").append(done).append(" image(s) exported successfully.\n\n");
        sb.append("Failed:\n");
        for (String f : failures)
            sb.append("• ").append(f).append("\n");

        Dialogs.showMessageDialog(
                "Export annotations — completed with errors (" + succeeded + "/" + done + ")",
                sb.toString());
    }

    /**
     * Give a short, user-facing reason for a failure, rather than a raw stack trace.
     */
    private static String describeFailure(Exception ex) {
        String msg = ex.getMessage();
        if (msg == null)
            return ex.getClass().getSimpleName();

        if (msg.contains("OpenSlide") && msg.contains("not a file that OpenSlide can recognize"))
            return "Image format not recognized (unsupported/non-pyramidal TIFF?)";

        if (ex instanceof java.io.FileNotFoundException || msg.toLowerCase().contains("no such file"))
            return "Source image file missing or moved";

        return msg;
    }


    private static class BatchResult {
        final int done;
        final List<String> failures;
        BatchResult(int done, List<String> failures) {
            this.done = done;
            this.failures = failures;
        }
    }

    private static void exportEntryAnnotations(ProjectImageEntry<BufferedImage> entry, File outputDir) throws Exception {
        ImageData<BufferedImage> imageData = entry.readImageData();
        try {
            var annotations = imageData.getHierarchy().getAnnotationObjects();

            String safeName = GeneralTools.stripInvalidFilenameChars(entry.getImageName());
            File outFile = new File(outputDir, safeName + ".geojson");

            PathIO.exportObjectsAsGeoJSON(
                    outFile,
                    annotations,
                    PathIO.GeoJsonExportOptions.FEATURE_COLLECTION);
        } finally {
            imageData.getServer().close();
        }
    }


}