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
    /**
     * Most columns of the overview, the finest unit that stays within is used.
     */
    public static final int MAX_BUCKETS = 60;

    private final LocalDateTime first;
    private final Duration bucket;
    private final String bucketName;
    private final List<LocalDateTime> buckets = new ArrayList<>();

    /**
     * A period with data, a gap or an anomaly, found independent of the buckets.
     */
    public record Event(LocalDateTime from, LocalDateTime until, String category) {

    }

    public AnomalyOverview(LocalDateTime start, LocalDateTime end) {
        this(start, end, 0);
    }

    /**
     * @param start Start of the period
     * @param end End of the period
     * @param minBucketSeconds Buckets are not finer than this, e.g. the measure interval
     */
    public AnomalyOverview(LocalDateTime start, LocalDateTime end, long minBucketSeconds) {
        // The finest of minutes, hours, days and weeks with at most MAX_BUCKETS columns
        // that is not finer than minBucketSeconds (finer buckets would mostly hold no value)
        long seconds = Duration.between(start, end).toSeconds();
        if (minBucketSeconds <= 60 && seconds <= MAX_BUCKETS * 60L) {
            this.bucket = Duration.ofMinutes(1);
            this.bucketName = "minute";
            this.first = start.truncatedTo(ChronoUnit.MINUTES);
        } else if (minBucketSeconds <= 3600 && seconds <= MAX_BUCKETS * 3600L) {
            this.bucket = Duration.ofHours(1);
            this.bucketName = "hour";
            this.first = start.truncatedTo(ChronoUnit.HOURS);
        } else if (minBucketSeconds <= 86400 && seconds <= MAX_BUCKETS * 86400L) {
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
     * @return minute, hour, day or week
     */
    public String getBucketName() {
        return this.bucketName;
    }

    /**
     * Runs all detectors and the gap check on the series. The result does not
     * depend on the buckets, so the bucket size can be chosen afterwards.
     *
     * @param series Values ordered by time
     * @return Periods with data (normal), gaps (missing) and anomalies
     */
    public static List<Event> analyse(List<TimeValue> series) {
        List<Event> events = new ArrayList<>();
        long interval = SeriesStatistics.medianIntervalSeconds(series);
        int runStart = 0;
        for (int i = 1; i <= series.size(); i++) {
            boolean gap = i < series.size() && interval > 0
                    && SeriesStatistics.secondsBetween(series.get(i - 1), series.get(i)) > interval * GAP_FACTOR;
            if (i == series.size() || gap) {
                events.add(new Event(series.get(runStart).time(), series.get(i - 1).time(), NORMAL));
                if (gap)
                    events.add(new Event(series.get(i - 1).time(), series.get(i).time(), MISSING));
                runStart = i;
            }
        }

        List<AnomalyDetector> detectors = List.of(new RateOfChangeDetector(null, null),
                new FlatlineDetector(null, null, null), new OscillationDetector(null, null, null));
        for (AnomalyDetector curDetector : detectors) {
            for (Anomaly curAnomaly : curDetector.detect(series))
                events.add(new Event(curAnomaly.getFrom(), curAnomaly.getUntil(), category(curAnomaly)));
        }
        return events;
    }

    /**
     * Gets the most important category per bucket.
     *
     * @param events Result of analyse()
     * @return Category per bucket
     */
    public String[] project(List<Event> events) {
        String[] cells = new String[this.buckets.size()];
        Arrays.fill(cells, NODATA);
        for (Event curEvent : events)
            this.mark(cells, curEvent.from(), curEvent.until(), curEvent.category());
        return cells;
    }

    public String[] categorize(List<TimeValue> series) {
        return this.project(analyse(series));
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
