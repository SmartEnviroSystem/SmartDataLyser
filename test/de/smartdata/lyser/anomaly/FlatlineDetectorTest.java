package de.smartdata.lyser.anomaly;

import java.util.List;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class FlatlineDetectorTest {

    @Test
    public void smoothSeriesHasNoAnomaly() {
        List<TimeValue> series = new SeriesBuilder().smooth(500).build();

        assertEquals(0, new FlatlineDetector(null, null, null).detect(series).size());
    }

    @Test
    public void stuckSensorIsFound() {
        List<TimeValue> series = new SeriesBuilder().smooth(200).constant(40, 18).smooth(200).build();

        List<Anomaly> anomalies = new FlatlineDetector(null, null, null).detect(series);

        assertEquals(1, anomalies.size());
        Anomaly flat = anomalies.get(0);
        assertEquals(series.get(200).time(), flat.getFrom());
        assertEquals(series.get(239).time(), flat.getUntil());
        assertEquals(18, flat.getIndicator(), 0.001);
    }

    @Test
    public void shortConstantPeriodIsIgnored() {
        // 5 values span one hour, default minimum is 10 intervals (150 minutes)
        List<TimeValue> series = new SeriesBuilder().smooth(100).constant(5, 18).smooth(100).build();

        assertEquals(0, new FlatlineDetector(null, null, null).detect(series).size());
    }

    @Test
    public void givenMinimumDurationIsUsed() {
        List<TimeValue> series = new SeriesBuilder().smooth(100).constant(5, 18).smooth(100).build();

        assertEquals(1, new FlatlineDetector(3600L, null, null).detect(series).size());
    }

    @Test
    public void valuesWithinEpsilonCountAsConstant() {
        List<TimeValue> series = new SeriesBuilder().smooth(100)
                .add(18).add(18.05).add(17.98).add(18.02).add(18.01).add(17.99)
                .smooth(100).build();

        assertEquals(0, new FlatlineDetector(3600L, 0.01, null).detect(series).size());
        assertEquals(1, new FlatlineDetector(3600L, 0.1, null).detect(series).size());
    }

    @Test
    public void dataGapEndsConstantPeriod() {
        // Both parts span 105 minutes, together they would span 225 minutes
        List<TimeValue> series = new SeriesBuilder().constant(8, 5).gap(10).constant(8, 5).build();

        assertEquals(0, new FlatlineDetector(7200L, 0.0, 900L).detect(series).size());
    }
}
