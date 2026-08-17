package qupath.lib.gui.tools.PDL1;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.tools.PDL1.PDL1Tools;
import qupath.lib.images.ImageData;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class PDL1Export {

    private PDL1Export() {}

    // 24-hour local timestamp (no AM/PM)
    private static final DateTimeFormatter TS_24H =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withLocale(Locale.US)
                    .withZone(ZoneId.systemDefault());

    public static void appendRow(ImageData<BufferedImage> imageData,
                                 String user,
                                 Float cps,
                                 int denomTumor,
                                 int nuclei,
                                 boolean toolMode) {

        var project = QuPathGUI.getInstance().getProject();
        if (project == null || imageData == null) return;

        ProjectImageEntry<BufferedImage> entry = project.getEntry(imageData);
        if (entry == null) return;

        var md = entry.getMetadata();
        if (md == null) return;

        String elapsedMsStr = md.get(PDL1Tools.PDL1Keys.TIMER_ELAPSED_MS);
        String stopMsStr    = md.get(PDL1Tools.PDL1Keys.TIMER_STOP_MS);

        // stop time formatted in 24-hour local time
        String stopTime24h = "";
        if (stopMsStr != null && !stopMsStr.isBlank()) {
            try {
                long stopMs = Long.parseLong(stopMsStr.trim());
                stopTime24h = TS_24H.format(Instant.ofEpochMilli(stopMs));
            } catch (Exception ignored) {}
        }

        // elapsed seconds (total seconds since start)
        String elapsedSeconds = "";
        String elapsedMsOut = "";
        if (elapsedMsStr != null && !elapsedMsStr.isBlank()) {
            try {
                long elapsedMs = Long.parseLong(elapsedMsStr.trim());
                elapsedMsOut = Long.toString(elapsedMs);
                elapsedSeconds = String.format(Locale.US, "%.2f", elapsedMs / 1000.0);
            } catch (Exception ignored) {}
        }

        String slideName = entry.getImageName();
        String exportedAt24h = TS_24H.format(Instant.now());

        Path outDir = getProjectOutputDir(project);
        Path outCsv = outDir.resolve("pdl1_results.csv");

        try {
            Files.createDirectories(outDir);

            boolean needsHeader = !Files.exists(outCsv);

            if (needsHeader) {
                String header =
                        "slide,user,mode,cps,denom_tumor,nuclei,elapsed_ms,elapsed_seconds,stop_time_24h,exported_at_24h\n";
                Files.writeString(outCsv, header, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }

            String row = String.join(",",
                    csv(slideName),
                    csv(user),
                    csv(toolMode ? "TOOL" : "NON_TOOL"),
                    csv(Float.toString(cps)),
                    csv(Integer.toString(denomTumor)),
                    csv(Integer.toString(nuclei)),
                    csv(elapsedMsOut),
                    csv(elapsedSeconds),
                    csv(stopTime24h),
                    csv(exportedAt24h)
            ) + "\n";

            Files.writeString(outCsv, row, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static Path getProjectOutputDir(Project<BufferedImage> project) {
        try {
            return Paths.get(project.getPath().toString()).getParent().resolve("pdl1_logs");
        } catch (Exception e) {
            return Paths.get(System.getProperty("user.home")).resolve("pdl1_logs");
        }
    }

    private static String csv(String s) {
        if (s == null) s = "";
        s = s.replace("\"", "\"\"");
        return "\"" + s + "\"";
    }
}
