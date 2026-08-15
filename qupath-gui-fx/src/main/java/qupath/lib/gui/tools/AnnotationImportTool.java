package qupath.lib.gui.tools;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.concurrent.Task;
import org.controlsfx.dialog.ProgressDialog;
import qupath.fx.dialogs.FileChoosers;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.io.PathIO;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.projects.ProjectImageEntry;


public class AnnotationImportTool {

    private static final Logger logger = LoggerFactory.getLogger(AnnotationImportTool.class);

    private AnnotationImportTool() {}

    // --- shared helper, put in a common location both tools can use ---
    private static Path getAnnotationsDirectory(Project<BufferedImage> project) throws IOException {
        Path path = project.getPath();
        if (path == null)
            throw new IOException("Project has no local file path (not stored on local filesystem)");

        Path baseDir = Files.isDirectory(path) ? path : path.getParent();
        Path annotationsDir = baseDir.resolve("annotations");
        Files.createDirectories(annotationsDir);
        return annotationsDir;
    }


    public static void importCurrentImageAnnotations(QuPathGUI qupath) {
        var viewer = qupath.getViewer();
        var imageData = viewer == null ? null : viewer.getImageData();
        if (imageData == null) {
            Dialogs.showInfoNotification("Import Annotations", "Open an image first.");
            return;
        }

        var project = qupath.getProject();
        if (project == null) {
            Dialogs.showInfoNotification("Import Annotations", "No project is open.");
            return;
        }

        var entry = project.getEntry(imageData);
        if (entry == null) {
            Dialogs.showInfoNotification("Import Annotations", "Current image is not part of the open project.");
            return;
        }

        Path annotationsDir;
        try {
            annotationsDir = getAnnotationsDirectory(project);
        } catch (IOException ex) {
            Dialogs.showErrorNotification("Import Annotations", ex.getMessage());
            return;
        }

        File geoJsonFile = findMatchingAnnotationFile(annotationsDir.toFile(), entry);

        if (geoJsonFile == null) {
            boolean searchManually = Dialogs.showConfirmDialog(
                    "Import Annotations",
                    "No matching annotation file found for \"" + entry.getImageName() +
                            "\" in the annotations folder.\n\nWould you like to search for one manually?");
            if (!searchManually)
                return;

            var filter = new javafx.stage.FileChooser.ExtensionFilter(
                    "GeoJSON (gzip)", "*.geojson.gz", "*.geojson");
            geoJsonFile = FileChoosers.promptForFile(qupath.getStage(), "Select annotation GeoJSON file", filter);
            if (geoJsonFile == null)
                return;
        }

        var existingAnnotations = imageData.getHierarchy().getAnnotationObjects();
        if (!existingAnnotations.isEmpty()) {
            boolean proceed = Dialogs.showConfirmDialog(
                    "Import Annotations",
                    "This slide already has " + existingAnnotations.size() +
                            " annotation(s). Import anyway? (This will add to, not replace, the existing ones.)");
            if (!proceed)
                return;
        }

        // --- background task with progress dialog ---
        File finalGeoJsonFile = geoJsonFile;
        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                updateMessage("Reading " + finalGeoJsonFile.getName() + " …");
                List<PathObject> objects = PathIO.readObjects(finalGeoJsonFile);

                updateMessage("Adding " + objects.size() + " object(s) to hierarchy …");
                imageData.getHierarchy().addObjects(objects);

                updateMessage("Saving …");
                entry.saveImageData(imageData);

                return objects.size();
            }
        };

        ProgressDialog progressDialog = new ProgressDialog(task);
        progressDialog.setTitle("Import Annotations");
        progressDialog.initOwner(qupath.getStage());

        task.setOnSucceeded(e -> {
            int count = task.getValue();
            Dialogs.showInfoNotification("Import Annotations",
                    "Imported " + count + " object(s) from " + finalGeoJsonFile.getName());
        });
        task.setOnFailed(e -> {
            logger.error("Failed to import annotations for {}", entry.getImageName(), task.getException());
            Dialogs.showErrorNotification("Import Annotations", "Import failed: " + task.getException());
        });

        Thread thread = new Thread(task, "annotation-import-thread");
        thread.setDaemon(true);
        thread.start();
        progressDialog.showAndWait();
    }

    /**
     * Reduce a name to just lowercase letters and digits, so filesystem-unsafe
     * characters (*, :, ?, spaces vs underscores, etc.) can't cause an
     * otherwise-matching name to miss.
     */
    private static String normalize(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    /**
     * Look for {@code <safeName>.geojson.gz} or {@code <safeName>.geojson} in the
     * given directory. Returns null if neither exists.
     */
    private static File findMatchingAnnotationFile(File dir, ProjectImageEntry<BufferedImage> entry) {
        String targetName = normalize(GeneralTools.stripExtension(entry.getImageName()));

        File[] candidates = dir.listFiles((d, name) ->
                name.toLowerCase().endsWith(".geojson.gz") || name.toLowerCase().endsWith(".geojson"));
        if (candidates == null)
            return null;

        for (File f : candidates) {
            String candidateName = f.getName();
            String base = candidateName.toLowerCase().endsWith(".geojson.gz")
                    ? candidateName.substring(0, candidateName.length() - ".geojson.gz".length())
                    : GeneralTools.stripExtension(candidateName);

            if (normalize(base).equals(targetName))
                return f;
        }
        return null;
    }
}