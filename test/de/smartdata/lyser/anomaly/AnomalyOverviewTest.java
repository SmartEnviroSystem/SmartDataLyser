package de.smartdata.lyser.anomaly;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.Test;

public class AnomalyOverviewTest {

    private static final LocalDateTime START = SeriesBuilder.START;
    // 3 days of values every 15 minutes
    private static final int THREE_DAYS = 3 * 96;

    private static String[] categorizeThreeDays(List<TimeValue> series) {
        return new AnomalyOverview(START, START.plusDays(3)).categorize(series);
    }

    @Test
    public void bucketSizeDependsOnPeriod() {
        AnomalyOverview halfHour = new AnomalyOverview(START, START.plusMinutes(30));
        assertEquals("minute", halfHour.getBucketName());
        assertEquals(30, halfHour.getBuckets().size());

        AnomalyOverview oneDay = new AnomalyOverview(START, START.plusDays(1));
        assertEquals("hour", oneDay.getBucketName());
        assertEquals(24, oneDay.getBuckets().size());

        // Up to 60 columns: 60 hours are still hours, 3 days are days
        assertEquals("hour", new AnomalyOverview(START, START.plusHours(60)).getBucketName());
        assertEquals("day", new AnomalyOverview(START, START.plusDays(3)).getBucketName());

        AnomalyOverview month = new AnomalyOverview(START, START.plusDays(30));
        assertEquals("day", month.getBucketName());
        assertEquals(30, month.getBuckets().size());

        // 2026-10-01 is a Thursday, weeks start on Monday
        AnomalyOverview quarter = new AnomalyOverview(START, START.plusDays(90));
        assertEquals("week", quarter.getBucketName());
        assertEquals(LocalDateTime.of(2026, 9, 28, 0, 0), quarter.getBuckets().get(0));
    }

    @Test
    public void minutesBetweenValuesAreNormal() {
        // Values every 15 minutes, minute buckets in between are filled
        String[] cells = new AnomalyOverview(START, START.plusMinutes(30)).categorize(new SeriesBuilder().smooth(3).build());
        assertEquals(30, cells.length);
        for (String curCell : cells)
            assertEquals("normal", curCell);
    }

    @Test
    public void smoothSeriesIsNormal() {
        String[] cells = categorizeThreeDays(new SeriesBuilder().smooth(THREE_DAYS).build());
        assertArrayEquals(new String[]{"normal", "normal", "normal"}, cells);
    }

    @Test
    public void spikeIsOutlierInItsBucket() {
        // Value 150 is on the second day
        String[] cells = categorizeThreeDays(new SeriesBuilder().smooth(150).add(60).smooth(THREE_DAYS - 151).build());
        assertArrayEquals(new String[]{"normal", "outlier", "normal"}, cells);
    }

    @Test
    public void flatlineOutranksTheJumpsAtItsEdges() {
        String[] cells = categorizeThreeDays(new SeriesBuilder().smooth(100).constant(40, 18).smooth(THREE_DAYS - 140).build());
        assertEquals("flatline", cells[1]);
    }

    @Test
    public void dataGapIsMissing() {
        String[] cells = categorizeThreeDays(new SeriesBuilder().smooth(100).gap(20).smooth(THREE_DAYS - 120).build());
        assertArrayEquals(new String[]{"normal", "missing", "normal"}, cells);
    }

    @Test
    public void bucketWithoutValuesHasNoData() {
        String[] cells = categorizeThreeDays(new SeriesBuilder().smooth(96).build());
        assertArrayEquals(new String[]{"normal", "nodata", "nodata"}, cells);
    }

    @Test
    public void moreImportantCategoryWins() {
        assertEquals("normal", AnomalyOverview.worse("nodata", "normal"));
        assertEquals("outlier", AnomalyOverview.worse("jump", "outlier"));
        assertEquals("missing", AnomalyOverview.worse("missing", "oscillation"));
    }
}
