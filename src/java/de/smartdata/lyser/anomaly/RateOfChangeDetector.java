package de.smartdata.lyser.anomaly;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds places where a value changes faster than plausible.
 *
 * The rate of change between two neighbouring values is measured per minute.
 * Consecutive steps that exceed the threshold are combined into one anomaly,
 * so a single spike (up and down again) is reported once.
 */
public class RateOfChangeDetector implements AnomalyDetector {

    public static final String TYPE = "rateofchange";
    /**
     * Robust z-score above which a rate counts as anomalous when no maximum is
     * given. 3.5 is the common limit for the modified z-score.
     */
    public static final double AUTO_ZSCORE = 3.5;
    /**
     * Steps longer than this multiple of the measure interval are data gaps
     * and not compared.
     */
    public static final double GAP_FACTOR = 3;

    private final Double maxDeltaPerMinute;
    private final Long measureFreqSeconds;
    private final Map<String, Object> usedParameters = new LinkedHashMap<>();

    /**
     * @param maxDeltaPerMinute Largest allowed change per minute, null to
     * determine it from the data
     * @param measureFreqSeconds Expected seconds between two values, null to
     * determine it from the data
     */
    public RateOfChangeDetector(Double maxDeltaPerMinute, Long measureFreqSeconds) {
        this.maxDeltaPerMinute = maxDeltaPerMinute;
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

        // Rate of the step from value i-1 to value i, NaN if not comparable
        double[] rates = new double[series.size()];
        List<Double> validRates = new ArrayList<>();
        for (int i = 0; i < series.size(); i++) {
            rates[i] = Double.NaN;
            if (i == 0) {
                continue;
            }
            double seconds = SeriesStatistics.secondsBetween(series.get(i - 1), series.get(i));
            if (seconds <= 0 || seconds > maxGap) {
                continue;
            }
            rates[i] = Math.abs(series.get(i).value() - series.get(i - 1).value()) / seconds * 60;
            validRates.add(rates[i]);
        }

        double threshold;
        if (this.maxDeltaPerMinute != null) {
            threshold = this.maxDeltaPerMinute;
        } else {
            double[] valid = validRates.stream().mapToDouble(Double::doubleValue).toArray();
            threshold = SeriesStatistics.median(valid)
                    + AUTO_ZSCORE * SeriesStatistics.MAD_SCALE * SeriesStatistics.mad(valid);
            automatic.add("maxdelta");
        }

        // Combine neighbouring steps above the threshold
        int groupStart = -1;
        int groupEnd = -1;
        double groupMaxRate = 0;
        for (int i = 1; i < series.size(); i++) {
            if (Double.isNaN(rates[i]) || rates[i] <= threshold) {
                continue;
            }
            if (groupStart >= 0 && groupEnd == i - 1) {
                groupEnd = i;
                groupMaxRate = Math.max(groupMaxRate, rates[i]);
            } else {
                if (groupStart >= 0) {
                    anomalies.add(new Anomaly(TYPE, series.subList(groupStart, groupEnd + 1), groupMaxRate));
                }
                groupStart = i - 1;
                groupEnd = i;
                groupMaxRate = rates[i];
            }
        }
        if (groupStart >= 0) {
            anomalies.add(new Anomaly(TYPE, series.subList(groupStart, groupEnd + 1), groupMaxRate));
        }

        this.usedParameters.put("maxdelta", threshold);
        this.usedParameters.put("measurefreq", interval);
        this.usedParameters.put("automatic", automatic);
        return anomalies;
    }

    @Override
    public Map<String, Object> getUsedParameters() {
        return this.usedParameters;
    }
}
