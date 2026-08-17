package qupath.lib.gui.tools.PDL1;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.tools.PDL1.PDL1Tools;
import qupath.lib.images.ImageData;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;

public final class PDL1Timer {

    private PDL1Timer() {}

    public static void start(ImageData<BufferedImage> imageData) {
        var entry = getEntry(imageData);
        if (entry == null)
            return;

        var md = entry.getMetadata();
        if (md == null)
            return;

        md.put(PDL1Tools.PDL1Keys.TIMER_START_MS, Long.toString(System.currentTimeMillis()));
        md.remove(PDL1Tools.PDL1Keys.TIMER_ELAPSED_MS);
    }

    public static void stop(ImageData<BufferedImage> imageData) {
        var entry = getEntry(imageData);
        if (entry == null)
            return;

        var md = entry.getMetadata();
        if (md == null)
            return;

        String startStr = md.get(PDL1Tools.PDL1Keys.TIMER_START_MS);
        if (startStr == null)
            return;

        try {
            long start = Long.parseLong(startStr);
            long now = System.currentTimeMillis();
            long elapsed = Math.max(0, now - start);

            // ✅ write BOTH stop time and elapsed time
            md.put(PDL1Tools.PDL1Keys.TIMER_STOP_MS, Long.toString(now));
            md.put(PDL1Tools.PDL1Keys.TIMER_ELAPSED_MS, Long.toString(elapsed));

        } catch (Exception ignored) {
        } finally {
            // optional: remove start so timer can't be reused accidentally
            md.remove(PDL1Tools.PDL1Keys.TIMER_START_MS);
        }
    }


    private static ProjectImageEntry<BufferedImage> getEntry(ImageData<BufferedImage> imageData) {
        if (imageData == null)
            return null;
        var project = QuPathGUI.getInstance().getProject();
        return project == null ? null : project.getEntry(imageData);
    }
}
