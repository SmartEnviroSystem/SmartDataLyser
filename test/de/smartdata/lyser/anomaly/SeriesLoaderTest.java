package de.smartdata.lyser.anomaly;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SeriesLoaderTest {

    @Test
    public void plainIdentifiersAreValid() {
        assertTrue(SeriesLoader.isValidName("ts"));
        assertTrue(SeriesLoader.isValidName("double_1"));
        assertTrue(SeriesLoader.isValidName("exampledata_timeseries"));
    }

    @Test
    public void namesThatCouldChangeQueriesAreInvalid() {
        assertFalse(SeriesLoader.isValidName(null));
        assertFalse(SeriesLoader.isValidName(""));
        assertFalse(SeriesLoader.isValidName("ts; DROP TABLE x"));
        assertFalse(SeriesLoader.isValidName("a,b"));
        assertFalse(SeriesLoader.isValidName("\"name\""));
        assertFalse(SeriesLoader.isValidName("1abc"));
    }
}
