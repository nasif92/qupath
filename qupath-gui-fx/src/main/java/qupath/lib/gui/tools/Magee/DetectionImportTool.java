package qupath.lib.gui.tools.Magee;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.concurrent.Task;

import qupath.fx.dialogs.Dialogs;
import qupath.fx.dialogs.FileChoosers;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.io.PathIO;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import org.controlsfx.dialog.ProgressDialog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DetectionImportTool {

    private static final Logger logger = LoggerFactory.getLogger(DetectionImportTool.class);

    private DetectionImportTool() {}

    public static void importCurrentImageDetections(QuPathGUI qupath) {
        var viewer = qupath.getViewer();
        var imageData = viewer == null ? null : viewer.getImageData();
        if (imageData == null) {
            Dialogs.showInfoNotification("Import Detections", "Open an image first.");
            return;
        }

        var project = qupath.getProject();
        if (project == null) {
            Dialogs.showInfoNotification("Import Detections", "No project is open.");
            return;
        }

        var entry = project.getEntry(imageData);
        if (entry == null) {
            Dialogs.showInfoNotification("Import Detections", "Current image is not part of the open project.");
            return;
        }

        Path detDir;
        try {
            detDir = DetectionImportTool.getDetectionDirectory(project);
        } catch (IOException ex) {
            Dialogs.showErrorNotification("Import Detections", ex.getMessage());
            return;
        }

        File geoJsonFile = findMatchingDetectionFile(detDir.toFile(), entry);

        if (geoJsonFile == null) {
            boolean searchManually = Dialogs.showConfirmDialog(
                    "Import Detections",
                    "No matching detection file found for \"" + entry.getImageName() +
                            "\" in the detections folder.\n\nWould you like to search for one manually?");
            if (!searchManually)
                return;

            var filter = new javafx.stage.FileChooser.ExtensionFilter(
                    "GeoJSON (gzip)", "*.geojson.gz", "*.geojson");
            geoJsonFile = FileChoosers.promptForFile(qupath.getStage(), "Select detection GeoJSON file", filter);
            if (geoJsonFile == null)
                return;
        }

        var existingDetections = imageData.getHierarchy().getDetectionObjects();
        if (!existingDetections.isEmpty()) {
            boolean proceed = Dialogs.showConfirmDialog(
                    "Import Detections",
                    "This slide already has " + existingDetections.size() +
                            " detection(s). Import anyway? (This will add to, not replace, the existing ones.)");
            if (!proceed)
                return;
        }

        File finalGeoJsonFile = geoJsonFile;
        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                updateMessage("Reading " + finalGeoJsonFile.getName() + " …");
                List<PathObject> allObjects = PathIO.readObjects(finalGeoJsonFile);

                List<PathObject> detections = allObjects.stream()
                        .filter(PathObject::isDetection)
                        .toList();

                if (detections.isEmpty()) {
                    logger.warn("No detection objects found in {} — file contained {} object(s) total",
                            finalGeoJsonFile.getName(), allObjects.size());
                }

                updateMessage("Adding " + detections.size() + " detection(s) to hierarchy …");
                imageData.getHierarchy().addObjects(detections);

                updateMessage("Saving …");
                entry.saveImageData(imageData);

                return detections.size();
            }
        };

        ProgressDialog progressDialog = new ProgressDialog(task);
        progressDialog.setTitle("Import Detections");
        if (qupath.getStage() != null)
            progressDialog.initOwner(qupath.getStage());

        task.setOnSucceeded(_ -> {
            int count = task.getValue();
            if (count == 0) {
                Dialogs.showInfoNotification("Import Detections",
                        finalGeoJsonFile.getName() + " contained no detection objects.");
            } else {
                Dialogs.showInfoNotification("Import Detections",
                        "Imported " + count + " detection(s) from " + finalGeoJsonFile.getName());
            }
        });
        task.setOnFailed(_ -> {
            logger.error("Failed to import detections for {}", entry.getImageName(), task.getException());
            Dialogs.showErrorNotification("Import Detections", "Import failed: " + task.getException());
        });

        Thread thread = new Thread(task, "detection-import-thread");
        thread.setDaemon(true);
        thread.start();
        progressDialog.showAndWait();
    }

    private static Path getDetectionDirectory(Project<BufferedImage> project) throws IOException {
        Path path = project.getPath();
        if (path == null)
            throw new IOException("Project has no local file path (not stored on local filesystem)");

        Path baseDir = Files.isDirectory(path) ? path : path.getParent();
        Path detDir = baseDir.resolve("detections");
        Files.createDirectories(detDir);
        return detDir;
    }


    private static File findMatchingDetectionFile(File dir, ProjectImageEntry<BufferedImage> entry) {
        String targetName = normalize(GeneralTools.stripExtension(entry.getImageName()));

        File[] candidates = dir.listFiles((_, name) ->
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

    private static String normalize(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}