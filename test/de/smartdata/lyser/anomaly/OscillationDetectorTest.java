package de.smartdata.lyser.anomaly;

import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class OscillationDetectorTest {

    @Test
    public void smoothSeriesHasNoAnomaly() {
        List<TimeValue> series = new SeriesBuilder().smooth(500).build();

        assertEquals(0, new OscillationDetector(null, null, null).detect(series).size());
    }

    @Test
    public void jumpingBetweenExtremesIsFound() {
        List<TimeValue> series = new SeriesBuilder().smooth(200).alternate(12, 0, 30).smooth(200).build();

        List<Anomaly> anomalies = new OscillationDetector(null, null, null).detect(series);

        assertEquals(1, anomalies.size());
        Anomaly oscillation = anomalies.get(0);
        // The alternating values are at index 200 to 211
        assertFalse(oscillation.getFrom().isAfter(series.get(200).time()));
        assertFalse(oscillation.getUntil().isBefore(series.get(211).time()));
        assertTrue(oscillation.getIndicator() >= 10);
    }

    @Test
    public void fewDirectionChangesAreIgnored() {
        List<TimeValue> series = new SeriesBuilder().smooth(200).alternate(2, 0, 30).smooth(200).build();

        assertEquals(0, new OscillationDetector(null, null, null).detect(series).size());
    }

    @Test
    public void smallStepsAreIgnored() {
        // Steps of 2 are far below 50 percent of the range of about 15
        List<TimeValue> series = new SeriesBuilder().smooth(200).alternate(12, 14, 16).smooth(200).build();

        assertEquals(0, new OscillationDetector(null, null, null).detect(series).size());
    }

    @Test
    public void constantSeriesHasNoAnomaly() {
        List<TimeValue> series = new SeriesBuilder().constant(100, 5).build();

        assertEquals(0, new OscillationDetector(null, null, null).detect(series).size());
    }
}
