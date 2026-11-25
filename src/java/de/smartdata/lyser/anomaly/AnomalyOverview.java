package de.smartdata.lyser.anomaly;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Divides a period into time buckets and assigns every bucket the most
 * important anomaly category found in a series.
 *
 * Categories, from least to most important: nodata, normal, jump, outlier,
 * flatline, oscillation, missing. A rate of change anomaly that returns
 * (at least three values) is an outlier, a single step is a jump. Flatline and
 * oscillation outrank them, because their edges always trigger rate of change
 * as well.
 */
public class AnomalyOverview {

    public static final String NODATA = "nodata";
    public static final String NORMAL = "normal";
    public static final String MISSING = "missing";
    public static final List<String> RANKING = List.of(NODATA, NORMAL, "jump", "outlier",
            FlatlineDetector.TYPE, OscillationDetector.TYPE, MISSING);
    /**
     * Steps longer than this multiple of the measure interval are missing values.
     */
    public static final double GAP_FACTOR = 3;

    private final LocalDateTime first;
    private final Duration bucket;
    private final String bucketName;
    private final List<LocalDateTime> buckets = new ArrayList<>();

    /**
     * @param start Start of the period
     * @param end End of the period
     */
    public AnomalyOverview(LocalDateTime start, LocalDateTime end) {
        // At most about 40 columns: hours up to 2 days, days up to 60 days, then weeks
        long hours = Duration.between(start, end).toHours();
        if (hours <= 48) {
            this.bucket = Duration.ofHours(1);
            this.bucketName = "hour";
            this.first = start.truncatedTo(ChronoUnit.HOURS);
        } else if (hours <= 60 * 24) {
            this.bucket = Duration.ofDays(1);
            this.bucketName = "day";
            this.first = start.truncatedTo(ChronoUnit.DAYS);
        } else {
            this.bucket = Duration.ofDays(7);
            this.bucketName = "week";
            LocalDateTime day = start.truncatedTo(ChronoUnit.DAYS);
            this.first = day.minusDays(day.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue());
        }
        for (LocalDateTime cur = this.first; cur.isBefore(end); cur = cur.plus(this.bucket))
            this.buckets.add(cur);
    }

    public List<LocalDateTime> getBuckets() {
        return this.buckets;
    }

    /**
     * @return hour, day or week
     */
    public String getBucketName() {
        return this.bucketName;
    }

    /**
     * Runs all detectors on the series and gets the most important category
     * per bucket.
     *
     * @param series Values ordered by time
     * @return Category per bucket
     */
    public String[] categorize(List<TimeValue> series) {
        String[] cells = new String[this.buckets.size()];
        Arrays.fill(cells, NODATA);
        for (TimeValue curValue : series)
            this.mark(cells, curValue.time(), curValue.time(), NORMAL);

        List<AnomalyDetector> detectors = List.of(new RateOfChangeDetector(null, null),
                new FlatlineDetector(null, null, null), new OscillationDetector(null, null, null));
        for (AnomalyDetector curDetector : detectors) {
            for (Anomaly curAnomaly : curDetector.detect(series))
                this.mark(cells, curAnomaly.getFrom(), curAnomaly.getUntil(), category(curAnomaly));
        }

        long interval = SeriesStatistics.medianIntervalSeconds(series);
        for (int i = 1; interval > 0 && i < series.size(); i++) {
            if (SeriesStatistics.secondsBetween(series.get(i - 1), series.get(i)) > interval * GAP_FACTOR)
                this.mark(cells, series.get(i - 1).time(), series.get(i).time(), MISSING);
        }
        return cells;
    }

    static String category(Anomaly anomaly) {
        if (!RateOfChangeDetector.TYPE.equals(anomaly.getType()))
            return anomaly.getType();
        return anomaly.getCount() >= 3 ? "outlier" : "jump";
    }

    /**
     * @return The more important of both categories
     */
    public static String worse(String first, String second) {
        return RANKING.indexOf(second) > RANKING.indexOf(first) ? second : first;
    }

    private void mark(String[] cells, LocalDateTime from, LocalDateTime until, String category) {
        int last = Math.min(this.index(until), cells.length - 1);
        for (int i = Math.max(this.index(from), 0); i <= last; i++)
            cells[i] = worse(cells[i], category);
    }

    private int index(LocalDateTime time) {
        return (int) Math.floorDiv(Duration.between(this.first, time).toSeconds(), this.bucket.toSeconds());
    }
}
