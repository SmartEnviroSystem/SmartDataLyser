package de.smartdata.lyser.anomaly;

import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class RateOfChangeDetectorTest {

    @Test
    public void smoothSeriesHasNoAnomaly() {
        List<TimeValue> series = new SeriesBuilder().smooth(500).build();

        List<Anomaly> anomalies = new RateOfChangeDetector(null, null).detect(series);

        assertEquals(0, anomalies.size());
    }

    @Test
    public void spikeIsReportedOnce() {
        List<TimeValue> series = new SeriesBuilder().smooth(200).add(60).smooth(200).build();

        List<Anomaly> anomalies = new RateOfChangeDetector(null, null).detect(series);

        assertEquals(1, anomalies.size());
        Anomaly spike = anomalies.get(0);
        // Value before the spike, the spike and the value after it
        assertEquals(series.get(199).time(), spike.getFrom());
        assertEquals(series.get(201).time(), spike.getUntil());
        assertEquals(3, spike.getCount());
        assertEquals(60, spike.getMax(), 0.001);
    }

    @Test
    public void givenThresholdIsUsed() {
        // Steps of 1.5 per 15 minutes are 0.1 per minute
        List<TimeValue> series = new SeriesBuilder().add(0).add(1.5).add(3).add(4.5).build();

        assertEquals(0, new RateOfChangeDetector(0.1, null).detect(series).size());
        assertEquals(1, new RateOfChangeDetector(0.09, null).detect(series).size());
    }

    @Test
    public void dataGapIsNoJump() {
        List<TimeValue> series = new SeriesBuilder().constant(20, 10).gap(10).constant(20, 30).build();

        List<Anomaly> anomalies = new RateOfChangeDetector(0.1, null).detect(series);

        assertEquals(0, anomalies.size());
    }

    @Test
    public void jumpWithoutGapIsFound() {
        List<TimeValue> series = new SeriesBuilder().constant(20, 10).constant(20, 30).build();

        List<Anomaly> anomalies = new RateOfChangeDetector(0.1, null).detect(series);

        assertEquals(1, anomalies.size());
    }

    @Test
    public void usedParametersAreReported() {
        RateOfChangeDetector detector = new RateOfChangeDetector(null, null);
        detector.detect(new SeriesBuilder().smooth(50).build());

        assertEquals(15L * 60, detector.getUsedParameters().get("measurefreq"));
        assertTrue(((List<?>) detector.getUsedParameters().get("automatic")).contains("maxdelta"));
    }
}
