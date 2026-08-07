package qupath.lib.gui.tools;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import qupath.fx.dialogs.FileChoosers;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.io.PathIO;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

        var filter = new javafx.stage.FileChooser.ExtensionFilter("GeoJSON (gzip)", "*.geojson.gz", "*.geojson");

        File geoJsonFile = FileChoosers.promptForFile(
                qupath.getStage(),
                "Select annotation GeoJSON file",
                filter);

        if (geoJsonFile == null)
            return; // cancelled

        try {
            List<PathObject> objects = PathIO.readObjects(geoJsonFile);
            imageData.getHierarchy().addObjects(objects);
            entry.saveImageData(imageData);
            Dialogs.showInfoNotification("Import Annotations",
                    "Imported " + objects.size() + " object(s) into " + entry.getImageName());
        } catch (Exception ex) {
            logger.error("Failed to import annotations for {}", entry.getImageName(), ex);
            Dialogs.showErrorNotification("Import Annotations", "Import failed: " + ex.getMessage());
        }
    }

}