package de.smartdata.lyser.anomaly;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Robust statistic functions used to determine thresholds automatically.
 *
 * Median and median absolute deviation (MAD) are used instead of mean and
 * standard deviation, because the outliers that should be found would
 * otherwise distort the thresholds.
 */
public final class SeriesStatistics {

    /**
     * Factor that makes the MAD comparable to the standard deviation of
     * normally distributed data.
     */
    public static final double MAD_SCALE = 1.4826;

    private SeriesStatistics() {
    }

    /**
     * Gets the median of the values.
     *
     * @param values Values (not modified)
     * @return Median or 0 if there are no values
     */
    public static double median(double[] values) {
        if (values.length == 0) {
            return 0;
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        if (sorted.length % 2 == 0) {
            return (sorted[mid - 1] + sorted[mid]) / 2;
        }
        return sorted[mid];
    }

    /**
     * Gets the median absolute deviation from the median.
     *
     * @param values Values (not modified)
     * @return MAD or 0 if there are no values
     */
    public static double mad(double[] values) {
        double median = median(values);
        double[] deviations = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            deviations[i] = Math.abs(values[i] - median);
        }
        return median(deviations);
    }

    /**
     * Gets the percentile of the values (nearest rank).
     *
     * @param values Values (not modified)
     * @param percent Percentile between 0 and 100
     * @return Value at the percentile or 0 if there are no values
     */
    public static double percentile(double[] values, double percent) {
        if (values.length == 0) {
            return 0;
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(percent / 100.0 * sorted.length) - 1;
        index = Math.max(0, Math.min(sorted.length - 1, index));
        return sorted[index];
    }

    /**
     * Gets the values of the series as array.
     *
     * @param series Series
     * @return Values in the order of the series
     */
    public static double[] values(List<TimeValue> series) {
        double[] values = new double[series.size()];
        for (int i = 0; i < series.size(); i++) {
            values[i] = series.get(i).value();
        }
        return values;
    }

    /**
     * Gets the typical time between two measurements.
     *
     * @param series Series ordered by time
     * @return Median interval in seconds or 0 if it can not be determined
     */
    public static long medianIntervalSeconds(List<TimeValue> series) {
        if (series.size() < 2) {
            return 0;
        }
        double[] intervals = new double[series.size() - 1];
        for (int i = 1; i < series.size(); i++) {
            intervals[i - 1] = secondsBetween(series.get(i - 1), series.get(i));
        }
        return Math.round(median(intervals));
    }

    /**
     * Gets the seconds between two measurements.
     *
     * @param first Earlier measurement
     * @param second Later measurement
     * @return Seconds (with fractions) between both
     */
    public static double secondsBetween(TimeValue first, TimeValue second) {
        return Duration.between(first.time(), second.time()).toMillis() / 1000.0;
    }
}
