package qupath.lib.gui.tools.Magee;

public class MageeEquations {

    private MageeEquations() {}

    /**
     * HER2 adjustment term shared by all three equations.
     * Mirrors the reference Python script's her_value branching.
     */
    public static double her2Value(int her2Ihc, double her2Sish) {
        if (her2Ihc == 0 || her2Ihc == 1)
            return 0.0;

        if (her2Ihc == 2) {
            if (her2Sish < 4)
                return 0.0;
            else if (her2Sish >= 4 && her2Sish < 6)
                return 0.77681;
            else if (her2Sish >= 6)
                return 11.58134;
        }

        if (her2Ihc == 3)
            return 11.58134;

        throw new IllegalArgumentException("Unexpected HER2 IHC value: " + her2Ihc);
    }

    public static double equation1(int nott, double erihc, double prihc,
                                   int her2Ihc, double her2Sish, double tumorSizeMm, double ki67) {
        double herValue = her2Value(her2Ihc, her2Sish);
        double tumorSizeCm = tumorSizeMm / 10.0;
        double result = 15.31385
                + (nott * 1.4055)
                + (erihc * -0.01924)
                + (prihc * -0.02925)
                + herValue
                + (tumorSizeCm * 0.78677)
                + (ki67 * 0.13269);
        return round(result);
    }

    public static double equation2(int nott, double erihc, double prihc,
                                   int her2Ihc, double her2Sish, double tumorSizeMm) {
        double herValue = her2Value(her2Ihc, her2Sish);
        double tumorSizeCm = tumorSizeMm / 10.0;
        double result = 18.8042
                + (nott * 2.34123)
                + (erihc * -0.03749)
                + (prihc * -0.03065)
                + herValue
                + (tumorSizeCm * 0.04267);
        return round(result);
    }

    public static double equation3(double erihc, double prihc,
                                   int her2Ihc, double her2Sish, double ki67) {
        double herValue = her2Value(her2Ihc, her2Sish);
        double result = 24.30812
                + (erihc * -0.02177)
                + (prihc * -0.02884)
                + herValue
                + (ki67 * 0.18649);
        return round(result);
    }

    /**
     * Mirrors the reference script's magee_equations_decision logic.
     */
    public static String decision(double me1, double me2, double me3, int mitoticScore) {
        if (me1 < 18 && me2 < 18 && me3 < 18)
            return "LOW ≤ 25";

        if (me1 >= 31 && me2 >= 31 && me3 >= 31)
            return "HIGH > 25";

        if ((me1 > 25 && me1 < 31) || (me2 > 25 && me2 < 31) || (me3 > 25 && me3 < 31))
            return "Perform ODX";

        if ((me1 > 18 && me1 < 25) || (me2 > 18 && me2 < 25) || (me3 > 18 && me3 < 25)) {
            if (mitoticScore == 1)
                return "LOW ≤ 25";
            else if (mitoticScore == 2 || mitoticScore == 3)
                return "Perform ODX";
        }

        return "Inconclusive — review manually";
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0; // 1 decimal place
    }
}