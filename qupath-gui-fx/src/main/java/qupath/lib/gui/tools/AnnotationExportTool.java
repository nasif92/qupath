package qupath.lib.gui.tools;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    public static void exportCurrentImageAnnotations(QuPathGUI qupath) {
        var viewer = qupath.getViewer();
        var imageData = viewer == null ? null : viewer.getImageData();
        if (imageData == null) {
            Dialogs.showInfoNotification("Export Annotations", "Open an image first.");
            return;
        }

        var project = qupath.getProject();
        if (project == null) {
            Dialogs.showInfoNotification("Export Annotations", "No project is open.");
            return;
        }

        var entry = project.getEntry(imageData);
        if (entry == null) {
            Dialogs.showInfoNotification("Export Annotations", "Current image is not part of the open project.");
            return;
        }

        Path annotationsDir;
        try {
            annotationsDir = getAnnotationsDirectory(project);
        } catch (IOException ex) {
            Dialogs.showErrorNotification("Export Annotations", ex.getMessage());
            return;
        }

        String safeName = GeneralTools.stripInvalidFilenameChars(entry.getImageName());
        File outFile = new File(annotationsDir.toFile(), safeName + ".geojson.gz");

        if (outFile.exists()) {
            boolean proceed = Dialogs.showConfirmDialog("Export Annotations",
                    outFile.getName() + " already exists. Overwrite?");
            if (!proceed)
                return;
        }

        try {
            var annotations = imageData.getHierarchy().getAnnotationObjects();
            PathIO.exportObjectsAsGeoJSON(outFile, annotations, PathIO.GeoJsonExportOptions.FEATURE_COLLECTION);
            Dialogs.showInfoNotification("Export Annotations", "Exported annotations to " + outFile.getName());
        } catch (Exception ex) {
            logger.error("Failed to export annotations for {}", entry.getImageName(), ex);
            Dialogs.showErrorNotification("Export Annotations", "Export failed: " + ex.getMessage());
        }
    }


}