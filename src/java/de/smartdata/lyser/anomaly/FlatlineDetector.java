package de.smartdata.lyser.anomaly;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds periods in which a value stays constant for too long, which often
 * indicates a stuck or failed sensor.
 *
 * A period counts as constant while all its values lie within a band of the
 * width epsilon. Data gaps end a period.
 */
public class FlatlineDetector implements AnomalyDetector {

    public static final String TYPE = "flatline";
    /**
     * Minimum number of values of a constant period. Two equal values in a row
     * are normal.
     */
    public static final int MIN_VALUES = 3;
    /**
     * Default minimum duration as multiple of the measure interval.
     */
    public static final int AUTO_DURATION_INTERVALS = 10;
    /**
     * Default epsilon as share of the typical step between two values. A
     * working sensor changes by about the typical step, a stuck one by much
     * less.
     */
    public static final double AUTO_EPSILON_FACTOR = 0.1;
    /**
     * Steps longer than this multiple of the measure interval are data gaps.
     */
    public static final double GAP_FACTOR = 3;

    private final Long minDurationSeconds;
    private final Double epsilon;
    private final Long measureFreqSeconds;
    private final Map<String, Object> usedParameters = new LinkedHashMap<>();

    /**
     * @param minDurationSeconds Minimum duration of a constant period, null to
     * determine it from the data
     * @param epsilon Allowed fluctuation within a constant period, null to
     * determine it from the data
     * @param measureFreqSeconds Expected seconds between two values, null to
     * determine it from the data
     */
    public FlatlineDetector(Long minDurationSeconds, Double epsilon, Long measureFreqSeconds) {
        this.minDurationSeconds = minDurationSeconds;
        this.epsilon = epsilon;
        this.measureFreqSeconds = measureFreqSeconds;
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

        long interval = this.measureFreqSeconds != null
                ? this.measureFreqSeconds : SeriesStatistics.medianIntervalSeconds(series);
        if (this.measureFreqSeconds == null) {
            automatic.add("measurefreq");
        }
        double maxGap = interval > 0 ? interval * GAP_FACTOR : Double.POSITIVE_INFINITY;

        long minDuration;
        if (this.minDurationSeconds != null) {
            minDuration = this.minDurationSeconds;
        } else {
            minDuration = interval * AUTO_DURATION_INTERVALS;
            automatic.add("minduration");
        }

        double eps;
        if (this.epsilon != null) {
            eps = this.epsilon;
        } else {
            List<Double> steps = new ArrayList<>();
            for (int i = 1; i < series.size(); i++) {
                if (SeriesStatistics.secondsBetween(series.get(i - 1), series.get(i)) <= maxGap) {
                    steps.add(Math.abs(series.get(i).value() - series.get(i - 1).value()));
                }
            }
            eps = AUTO_EPSILON_FACTOR * SeriesStatistics.median(
                    steps.stream().mapToDouble(Double::doubleValue).toArray());
            automatic.add("epsilon");
        }

        if (!series.isEmpty()) {
            int start = 0;
            double runMin = series.get(0).value();
            double runMax = runMin;
            for (int i = 1; i <= series.size(); i++) {
                if (i < series.size()) {
                    double value = series.get(i).value();
                    boolean noGap = SeriesStatistics.secondsBetween(series.get(i - 1), series.get(i)) <= maxGap;
                    if (noGap && Math.max(runMax, value) - Math.min(runMin, value) <= eps) {
                        runMin = Math.min(runMin, value);
                        runMax = Math.max(runMax, value);
                        continue;
                    }
                }
                // Period ends before value i
                int end = i - 1;
                if (end - start + 1 >= MIN_VALUES
                        && SeriesStatistics.secondsBetween(series.get(start), series.get(end)) >= minDuration) {
                    anomalies.add(new Anomaly(TYPE, series.subList(start, end + 1), (runMin + runMax) / 2));
                }
                if (i < series.size()) {
                    start = i;
                    runMin = series.get(i).value();
                    runMax = runMin;
                }
            }
        }

        this.usedParameters.put("minduration", minDuration);
        this.usedParameters.put("epsilon", eps);
        this.usedParameters.put("measurefreq", interval);
        this.usedParameters.put("automatic", automatic);
        return anomalies;
    }

    @Override
    public Map<String, Object> getUsedParameters() {
        return this.usedParameters;
    }
}
