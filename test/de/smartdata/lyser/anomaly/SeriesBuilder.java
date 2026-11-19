package de.smartdata.lyser.anomaly;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates test series with one value every 15 minutes.
 */
class SeriesBuilder {

    static final LocalDateTime START = LocalDateTime.of(2026, 10, 1, 0, 0);
    static final int INTERVAL_MINUTES = 15;

    private final List<TimeValue> series = new ArrayList<>();
    // Number of left out time slots, see gap()
    private int skippedSlots = 0;

    /**
     * Adds values of a smooth daily curve with small, reproducible noise.
     */
    SeriesBuilder smooth(int count) {
        for (int i = 0; i < count; i++) {
            int slot = this.series.size() + this.skippedSlots;
            double hours = slot * INTERVAL_MINUTES / 60.0;
            this.add(15 + 8 * Math.sin(2 * Math.PI * hours / 24) + 0.3 * Math.sin(slot * 12.9898));
        }
        return this;
    }

    SeriesBuilder constant(int count, double value) {
        for (int i = 0; i < count; i++) {
            this.add(value);
        }
        return this;
    }

    SeriesBuilder alternate(int count, double low, double high) {
        for (int i = 0; i < count; i++) {
            this.add(i % 2 == 0 ? low : high);
        }
        return this;
    }

    SeriesBuilder add(double value) {
        int slot = this.series.size() + this.skippedSlots;
        this.series.add(new TimeValue(START.plusMinutes((long) slot * INTERVAL_MINUTES), value));
        return this;
    }

    /**
     * Leaves out the given number of time slots, which creates a data gap.
     */
    SeriesBuilder gap(int slots) {
        this.skippedSlots += slots;
        return this;
    }

    List<TimeValue> build() {
        return this.series;
    }
}
