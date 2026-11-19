package de.smartdata.lyser.anomaly;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds periods in which a value keeps jumping between extremes, for example
 * because of a loose contact.
 *
 * A reversal is a large step in one direction directly followed by a large
 * step in the other direction. A window of values is anomalous if it contains
 * at least minChanges reversals. Overlapping anomalous windows are combined.
 */
public class OscillationDetector implements AnomalyDetector {

    public static final String TYPE = "oscillation";
    public static final int AUTO_WINDOW = 10;
    public static final int AUTO_MIN_CHANGES = 4;
    public static final double AUTO_MIN_AMPLITUDE_PERCENT = 50;
    /**
     * The range of the series is measured between these percentiles, so
     * single outliers do not stretch it.
     */
    public static final double RANGE_LOWER_PERCENTILE = 5;
    public static final double RANGE_UPPER_PERCENTILE = 95;

    private final Integer window;
    private final Integer minChanges;
    private final Double minAmplitudePercent;
    private final Map<String, Object> usedParameters = new LinkedHashMap<>();

    /**
     * @param window Number of values looked at together, null for default
     * @param minChanges Minimum number of reversals within a window, null for
     * default
     * @param minAmplitudePercent Minimum step size in percent of the series
     * range, null for default
     */
    public OscillationDetector(Integer window, Integer minChanges, Double minAmplitudePercent) {
        this.window = window;
        this.minChanges = minChanges;
        this.minAmplitudePercent = minAmplitudePercent;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public List<Anomaly> detect(List<TimeValue> series) {
        this.usedParameters.clear();
        List<String> automatic = new ArrayList<>();
        List<Anomaly> anomalies = new ArrayList<>();

        int win = this.window != null ? this.window : AUTO_WINDOW;
        int changes = this.minChanges != null ? this.minChanges : AUTO_MIN_CHANGES;
        double amplitude = this.minAmplitudePercent != null ? this.minAmplitudePercent : AUTO_MIN_AMPLITUDE_PERCENT;
        if (this.window == null) {
            automatic.add("window");
        }
        if (this.minChanges == null) {
            automatic.add("minchanges");
        }
        if (this.minAmplitudePercent == null) {
            automatic.add("minamplitude");
        }

        double[] values = SeriesStatistics.values(series);
        double range = SeriesStatistics.percentile(values, RANGE_UPPER_PERCENTILE)
                - SeriesStatistics.percentile(values, RANGE_LOWER_PERCENTILE);
        double minStep = amplitude / 100 * range;

        this.usedParameters.put("window", win);
        this.usedParameters.put("minchanges", changes);
        this.usedParameters.put("minamplitude", amplitude);
        this.usedParameters.put("minstep", minStep);
        this.usedParameters.put("automatic", automatic);

        if (series.size() < 3 || range <= 0) {
            return anomalies;
        }
        win = Math.min(win, series.size());

        // reversals[i] is true if the series turns at value i with large steps
        // on both sides; reversalSum[i] counts reversals before index i
        boolean[] reversals = new boolean[series.size()];
        int[] reversalSum = new int[series.size() + 1];
        for (int i = 0; i < series.size(); i++) {
            if (i > 0 && i < series.size() - 1) {
                double before = values[i] - values[i - 1];
                double after = values[i + 1] - values[i];
                reversals[i] = Math.abs(before) >= minStep && Math.abs(after) >= minStep
                        && Math.signum(before) != Math.signum(after);
            }
            reversalSum[i + 1] = reversalSum[i] + (reversals[i] ? 1 : 0);
        }

        // Mark all values belonging to an anomalous window
        boolean[] inAnomaly = new boolean[series.size()];
        for (int start = 0; start + win <= series.size(); start++) {
            int end = start + win - 1;
            // Only inner values of the window can be reversals
            int count = reversalSum[end] - reversalSum[start + 1];
            if (count >= changes) {
                for (int i = start + 1; i < end; i++) {
                    if (reversals[i]) {
                        // A reversal involves its two neighbours
                        inAnomaly[i - 1] = true;
                        inAnomaly[i] = true;
                        inAnomaly[i + 1] = true;
                    }
                }
            }
        }

        // Combine marked values into periods
        int start = -1;
        for (int i = 0; i <= series.size(); i++) {
            boolean marked = i < series.size() && inAnomaly[i];
            if (marked && start < 0) {
                start = i;
            } else if (!marked && start >= 0) {
                int count = reversalSum[i] - reversalSum[start];
                anomalies.add(new Anomaly(TYPE, series.subList(start, i), count));
                start = -1;
            }
        }
        return anomalies;
    }

    @Override
    public Map<String, Object> getUsedParameters() {
        return this.usedParameters;
    }
}
