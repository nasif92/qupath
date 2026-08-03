package qupath.lib.gui.tools;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.ButtonBar;
import org.controlsfx.control.ListSelectionView;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.ProjectDialogs;
import qupath.lib.projects.ProjectImageEntry;

public class ImageSelectionTool {

    private ImageSelectionTool() {}

    /**
     * Show the same "available / selected" image picker used by QuPath's
     * "Run for project" command, and return the images the user selected.
     *
     * @return the selected entries, or null if the user cancelled
     */
    public static List<ProjectImageEntry<BufferedImage>> promptToSelectImages(
            QuPathGUI qupath, List<ProjectImageEntry<BufferedImage>> availableImages) {

        List<ProjectImageEntry<BufferedImage>> initiallySelected = new ArrayList<>();

        ListSelectionView<ProjectImageEntry<BufferedImage>> view =
                ProjectDialogs.createImageChoicePane(qupath, availableImages, initiallySelected, null);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Select images");
        dialog.getDialogPane().setContent(view);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResizable(true);

        if (qupath.getStage() != null)
            dialog.initOwner(qupath.getStage());

        var result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK)
            return null; // cancelled

        // Read the actual selection from the widget itself — 'initiallySelected'
        // was only ever a starting seed, never mutated by user interaction
        List<ProjectImageEntry<BufferedImage>> selected = new ArrayList<>(view.getTargetItems());

        return selected.isEmpty() ? null : selected;
    }
}