package qupath.lib.gui.tools.PDL1;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.tools.PDL1.PDL1ScoringPane;
import qupath.lib.gui.tools.PDL1.PDL1Timer;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.regions.ImageRegion;
import qupath.lib.roi.interfaces.ROI;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;


public class PDL1Tools {
    // Debounce state for the box updater
    private static final ScheduledExecutorService PDL1_EXEC =
            Executors.newSingleThreadScheduledExecutor(r -> { var t=new Thread(r,"PDL1-view"); t.setDaemon(true); return t; });

    private static volatile String CURRENT_USER = "DEFAULT USER";
    private static ScheduledFuture<?> viewFuture;

    public static void magnify_viewer(QuPathViewer viewer) {
        // 1️⃣ Save current center in image coordinates
        double cx = viewer.getCenterPixelX();
        double cy = viewer.getCenterPixelY();

        // 2️⃣ Compute downsample for 20x
        double nativeMag = viewer.getServer()
                .getMetadata()
                .getMagnification();

        if (Double.isNaN(nativeMag) || nativeMag <= 0)
            return;

        double downsample = nativeMag / 20.0;

        // 3️⃣ Apply zoom
        viewer.setDownsampleFactor(downsample);

        // 4️⃣ Restore center (important!)
        viewer.setCenterPixelLocation(cx, cy);
    }

    public static String getCurrentUser() {
        return CURRENT_USER;
    }

    public static void setCurrentUser(String currentUser) {
        CURRENT_USER = currentUser;
    }


    public static final class PDL1Keys {
        public static final String TIMER_START_MS   = "PDL1_TIMER_START_MS";
        public static final String TIMER_ELAPSED_MS = "PDL1_TIMER_ELAPSED_MS";
        public static final String TIMER_STOP_MS    = "PDL1_TIMER_STOP_MS";
        public static final String CPS_SCORE        = "PDL1_CPS";
        private PDL1Keys() {}
    }

    private static final java.util.concurrent.atomic.AtomicInteger LAST_DENOM_TUMOR = new java.util.concurrent.atomic.AtomicInteger(0);
    private static final java.util.concurrent.atomic.AtomicInteger LAST_NUCLEI     = new java.util.concurrent.atomic.AtomicInteger(0);

    public static int getLastDenomTumor() { return LAST_DENOM_TUMOR.get(); }
    public static int getLastNuclei()     { return LAST_NUCLEI.get(); }

    static final class PDL1ProjectUtil {
        static qupath.lib.projects.ProjectImageEntry<BufferedImage> getEntry(ImageData<BufferedImage> imageData) {
            if (imageData == null) return null;
            var project = QuPathGUI.getInstance().getProject();
            return project == null ? null : project.getEntry(imageData);
        }
    }
    private static PDL1ScoringPane scoringPane;
    private static QuPathViewer activeViewer;

    public static void showScoringPane(QuPathViewer viewer, boolean toolMode) {
        activeViewer = viewer;
        if (scoringPane == null)
            scoringPane = new PDL1ScoringPane();
        scoringPane.setToolMode(toolMode);
        scoringPane.bindTo(viewer.getImageData());

    }

    public static Float promptForCpsScore(Float current) {
        TextInputDialog dlg = new TextInputDialog(current == null ? "" : current.toString());
        dlg.setTitle("Enter CPS score (0-100)");
        dlg.setHeaderText(null);          // ✅ removes header (and icon)
        dlg.setGraphic(null);
        dlg.setContentText("CPS score:");

        var res = dlg.showAndWait();
        if (res.isEmpty())
            return null; // user cancelled

        String txt = res.get().trim();
        if (txt.isEmpty())
            return null;

        try {
            float cps = Float.parseFloat(txt);
            if (cps < 0 || cps > 100) {
                Dialogs.showErrorMessage("PD-L1", "CPS must be 0–100.");
                return null;
            }
            return cps;
        } catch (Exception e) {
            Dialogs.showErrorMessage("PD-L1", "CPS must be a number (0–100).");
            return null;
        }
    }

    public static void endScoringSession() {

        stopViewportCounter();
        var gui = QuPathGUI.getInstance();
        if (gui != null) {
            gui.hidePdl1ScoringPane();
        }

        activeViewer = null;
    }


    public static Float readCpsFromProjectMetadata(ImageData<BufferedImage> imageData) {
        var entry = PDL1ProjectUtil.getEntry(imageData);
        if (entry == null) return null;
        var md = entry.getMetadata();
        if (md == null) return null;
        String v = md.get(PDL1Keys.CPS_SCORE);
        if (v == null) return null;
        v = v.trim();
        if (v.isEmpty() || v.equalsIgnoreCase("null")) return null;
        try { return Float.parseFloat(v); }
        catch (Exception e) { return null; }
    }

    public static boolean writeCpsToProjectMetadata(ImageData<BufferedImage> imageData, Float cps) {
        var entry = PDL1ProjectUtil.getEntry(imageData);
        if (entry == null) {
            Dialogs.showErrorMessage("PD-L1", "Add the image to a project to store CPS.");
            return false;
        }
        var md = entry.getMetadata();
        if (md == null) return false;
        md.put(PDL1Keys.CPS_SCORE, Float.toString(cps));
        return true;
    }


    // === HUD label we overlay on top of the viewer ===
    private static Label HUD;
    private static StackPane HUDContainer; // the StackPane we attach to

    private static StackPane findStackPane(Node node) {
        Parent p = node.getParent();
        while (p != null && !(p instanceof StackPane)) {
            p = p.getParent();
        }
        return (StackPane)p;
    }

    private static void attachHud(QuPathViewer viewer) {
        if (HUD != null) return; // already attached

        var viewNode = viewer.getView(); // JavaFX Node rendered by the viewer
        var stack = findStackPane(viewNode);
        if (stack == null) return;

        HUD = new Label("CPS Thresholds");
        HUD.setMouseTransparent(true);
        HUD.setStyle("""
        -fx-background-color: rgba(0,0,0,0.6);
        -fx-text-fill: white;
        -fx-font-size: 13px;
        -fx-padding: 4 8 4 8;
        -fx-background-radius: 6;
    """);

        HUDContainer = stack;
        Pos pos = Pos.TOP_LEFT;
        Platform.runLater(() -> {
            StackPane.setAlignment(HUD, pos);
            StackPane.setMargin(HUD, new Insets(8, 8, 8, 8));
            stack.getChildren().add(HUD);
        });
    }

    private static void detachHud() {
        if (HUD != null && HUDContainer != null) {
            var toRemove = HUD;
            var parent = HUDContainer;
            HUD = null;
            HUDContainer = null;
            Platform.runLater(() -> parent.getChildren().remove(toRemove));
        }
    }

    private static void setHudText(String s) {
        if (HUD == null) return;
        Platform.runLater(() -> HUD.setText(s == null ? "" : s));
    }

    public static double[] showPdl1Popup(ImageData<BufferedImage> imageData) {
        Dialog<double[]> dialog = new Dialog<>();
        dialog.setTitle("CPS or TPS thresholds");

        ButtonType applyType = new ButtonType("Apply", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(applyType, ButtonType.CANCEL);

        TextField t1 = new TextField("1");
        TextField t2 = new TextField("5");
        TextField t3 = new TextField("10");

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(10); grid.setPadding(new Insets(10));
        grid.addRow(0, new Label("Threshold 1:"), t1);
        grid.addRow(1, new Label("Threshold 2:"), t2);
        grid.addRow(2, new Label("Threshold 3:"), t3);

        // simple numeric guard
        Node applyBtn = dialog.getDialogPane().lookupButton(applyType);
        applyBtn.setDisable(false);
        ChangeListener<String> guard = (_, _, _) -> {
            boolean anyValid = false;
            for (TextField tf : new TextField[]{t1, t2, t3}) {
                String txt = tf.getText().trim();
                if (!txt.isEmpty()) {
                    try {
                        Double.parseDouble(txt);
                        anyValid = true;
                        break;
                    } catch (Exception ignored) {}
                }
            }
            applyBtn.setDisable(!anyValid);
        };
        t1.textProperty().addListener(guard);
        t2.textProperty().addListener(guard);
        t3.textProperty().addListener(guard);

        dialog.getDialogPane().setContent(grid);

        // ✅ return only entered numeric thresholds
        dialog.setResultConverter(btn -> {
            if (btn == applyType) {
                // start timer here
                PDL1Timer.start(imageData);

                List<Double> vals = new ArrayList<>();
                for (TextField tf : new TextField[]{t1, t2, t3}) {
                    String txt = tf.getText().trim();
                    if (!txt.isEmpty()) {
                        try {
                            vals.add(Double.parseDouble(txt));
                        } catch (Exception ignored) {}
                    }
                }
                return vals.stream().mapToDouble(Double::doubleValue).toArray();
            }
            return null;
        });

        var result = dialog.showAndWait();
        return result.orElse(null);

    }

    public static void startViewportCounter(QuPathViewer viewer) {
        stopViewportCounter(); // ensure only one running at a time
        attachHud(viewer);

        viewFuture = PDL1_EXEC.scheduleAtFixedRate(() -> {
            try {
                int[] events = countNucleiInViewport(viewer);
                // Defensive checks
                int denomTumor = events.length > 0 ? events[0] : 0;  // tumor
                int nuclei     = events.length > 1 ? events[1] : 0;  // total
                int cpsScore   = events.length > 2 ? events[2] : 0;

                LAST_DENOM_TUMOR.set(denomTumor);
                LAST_NUCLEI.set(nuclei);

                // 4) Update HUD on the JavaFX thread
                Platform.runLater(() -> {
                    if (scoringPane != null) {
                        scoringPane.updateCounts(denomTumor, nuclei);
                    }
                    if (denomTumor == 0 && nuclei == 0) {
                        setHudText("No cells detected");
                    } else {
                        StringBuilder sb = new StringBuilder();
                        sb.append(
                                "CPS Thresholds: "
                        );
                        int[] thresholds = {1, 5, 10, 15, 20, 25, 30, 35, 40, 50};

                        for (int p : thresholds) {
                            double c = denomTumor * p / 100.0;

                            sb.append(String.format(
                                    "%n%d → %.0f",
                                    p, c
                            ));
                        }
                        setHudText(sb.toString());
                    }
                });
            } catch (Throwable err) {
                err.printStackTrace();
            }
        }, 0, 250, TimeUnit.MILLISECONDS);
    }

    static boolean isTumor(String name) {
        if (name == null)
            return false;

        String n = name.toLowerCase().trim();

        // Ignore "non-tumor" cases first
        if (n.contains("immune") || n.contains("stroma") || n.contains("necrosis") || n.contains("other"))
            return false;

        // Check if it contains any tumor-related keywords
        return n.contains("tumor") || n.contains("tumour") || n.contains("cancer");
    }

    static boolean isImmune(String s) {
        if (s == null)
            return false;
        String n = s.toLowerCase();
        return n.contains("immune");
    }

    /*/
    Function to count PD-L1 events in the view
     */
    // Return: [tumor, total, denomTumor, pTumor, pImmune, cps]
    private static int[] countNucleiInViewport(QuPathViewer viewer) {
        var imageData = viewer.getImageData();
        var hier = imageData.getHierarchy();

        // --- visible region in image coords ---
        double ds = viewer.getDownsampleFactor();
        double cx = viewer.getCenterPixelX(), cy = viewer.getCenterPixelY();
        double viewW = viewer.getView().getWidth()  * ds;
        double viewH = viewer.getView().getHeight() * ds;

        int x = (int)Math.floor(cx - viewW/2.0);
        int y = (int)Math.floor(cy - viewH/2.0);
        int w = (int)Math.ceil(viewW);
        int h = (int)Math.ceil(viewH);

        var server = imageData.getServer();
        if (x < 0) { w += x; x = 0; }
        if (y < 0) { h += y; y = 0; }
        if (x + w > server.getWidth())  w = server.getWidth()  - x;
        if (y + h > server.getHeight()) h = server.getHeight() - y;
        if (w <= 0 || h <= 0) return new int[]{0,0,0,0,0,0};

        var plane  = viewer.getImagePlane();
        var region = ImageRegion.createInstance(x, y, w, h, plane.getZ(), plane.getT());

        // Prefer detections collection typed as PathDetectionObject if your API returns it
        // Build a single list that includes both detections & annotations
        List<PathObject> objs = new ArrayList<>();
        objs.addAll(hier.getAllDetectionsForRegion((region)));
        objs.addAll(hier.getAllPointAnnotations());
        int total = 0, tumor = 0;
        int pTumor = 0, pImmune = 0;

        final int rx = region.getX(), ry = region.getY(), rw = region.getWidth(), rh = region.getHeight();

        for (PathObject obj : objs) {
//            if (!(det instanceof qupath.lib.objects.PathDetectionObject d)) continue;

            if (!(obj instanceof PathDetectionObject)) continue;
            PathDetectionObject d = (PathDetectionObject) obj;

            ROI roi = d.getROI();
            if (roi == null) continue;

            var cxDet = d.getROI().getCentroidX();
            var cyDet = d.getROI().getCentroidY();
            // keep only detections whose centroid lies inside the viewport rectangle
            if (cxDet < rx || cyDet < ry || cxDet >= rx + rw || cyDet >= ry + rh)
                continue;

            total++;
            String name =
                    obj.getPathClass() != null ? obj.getPathClass().getName()
                            : (obj.getName() != null ? obj.getName() : "");
            if (obj.getPathClass() != null) name = obj.getPathClass().getName();
            else if (obj.getName() != null) name = obj.getName();

            boolean isTumorCell  = isTumor(name);
            boolean isImmuneCell = !isTumorCell && isImmune(name);

            if (isTumorCell) {
                tumor++;
                if (isPDL1Positive(d)) pTumor++;
            } else if (isImmuneCell) {
                if (isPDL1Positive(d)) pImmune++;
            }
        }

        int cps = computeCPS(pTumor, pImmune, tumor);

        return new int[]{tumor, total, cps};
    }


    public static void stopViewportCounter() {
        if (viewFuture != null) {
            viewFuture.cancel(true);
            viewFuture = null;
        }
//        setHudText("");
        detachHud();
    }

    // Returns CPS (0..100), rounded down
    private static int computeCPS(int pdL1PosTumor, int pdL1PosImmune, int viableTumorCells) {
        if (viableTumorCells <= 0) return 0;
        double cps = 100.0 * (pdL1PosTumor + pdL1PosImmune) / viableTumorCells;
        return (int)Math.floor(cps);
    }


    private static double m(MeasurementList ml) {
        return (ml == null) ? Double.NaN : ml.get("PD-L1 positive");
    }


// Utility: try multiple possible measurement keys and return the first valid one
    private static double mAny(MeasurementList ml, String... keys) {
        if (ml == null) return Double.NaN;
        for (var k : keys) {
            double v = ml.get(k);
            if (!Double.isNaN(v)) return v;
        }
        return Double.NaN;
    }

    static boolean isPDL1Positive(PathDetectionObject d) {
        var ml = d.getMeasurementList();

        // Prefer explicit flag if present
        double flag = m(ml);
        if (!Double.isNaN(flag)) return flag >= 0.5;

        // Fallback to intensity-based (adjust keys to your data)
        double v = mAny(ml,
                "Cell: PD-L1 OD mean",
                "Cell: DAB mean",
                "Nucleus: DAB mean",
                "Membrane: DAB mean"
        );
        return !Double.isNaN(v) && v > 0.15; // tune threshold to your assay
    }

    /*
    * User prompt with privacy statement when using the PD-L1 tool
    * */
    public static String promptForUserWithPrivacy() {

        // ---- Step 1: Privacy notice ----
        Alert notice = getAlert();

        ButtonType next = new ButtonType("Next", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        notice.getButtonTypes().setAll(next, cancel);

        var choice = notice.showAndWait();
        if (choice.isEmpty() || choice.get() != next)
            return null; // user declined

        // ---- Step 2: Ask for name ----
        TextInputDialog nameDlg = new TextInputDialog(getCurrentUser());
        nameDlg.setTitle("Participant name");
        nameDlg.setHeaderText(null);
        nameDlg.setGraphic(null);
        nameDlg.setContentText("Enter your name:");

        var res = nameDlg.showAndWait();
        if (res.isEmpty())
            return null;

        String name = res.get().trim();
        if (name.isBlank()) {
            Dialogs.showErrorMessage("PD-L1", "Name is required to proceed.");
            return null;
        }

        return name;
    }

    // Privacy alert
    private static Alert getAlert() {
        Alert notice = new Alert(Alert.AlertType.CONFIRMATION);
        notice.setTitle("PD-L1 Scoring – Privacy notice");
        notice.setHeaderText("Participant data will be recorded");
        notice.setGraphic(null);

        notice.setContentText(
                """
                        This PD-L1 scoring tool records:
                        • Your name (as entered)
                        • Slide/image name
                        • CPS score
                        • Counts (tumor denominator, nuclei)
                        • Start/stop time and elapsed time
                        
                        This data is used for research/quality evaluation of scoring performance.
                        Data is saved locally to the project’s output log (CSV).
                        
                        By clicking Next, you consent to this collection."""
        );
        return notice;
    }

}
