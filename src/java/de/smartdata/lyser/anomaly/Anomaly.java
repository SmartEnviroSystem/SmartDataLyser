package de.smartdata.lyser.anomaly;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A period of a time series that was detected as anomalous.
 */
public class Anomaly {

    private final String type;
    private final LocalDateTime from;
    private final LocalDateTime until;
    private final int count;
    private final double min;
    private final double max;
    private final double indicator;

    /**
     * Creates an anomaly covering the given values.
     *
     * @param type Type of the detector that found the anomaly
     * @param values Values belonging to the anomaly, ordered by time (at least one)
     * @param indicator Detector specific key figure (e.g. the highest rate of change)
     */
    public Anomaly(String type, List<TimeValue> values, double indicator) {
        this.type = type;
        this.from = values.get(0).time();
        this.until = values.get(values.size() - 1).time();
        this.count = values.size();
        double curMin = Double.POSITIVE_INFINITY;
        double curMax = Double.NEGATIVE_INFINITY;
        for (TimeValue curValue : values) {
            curMin = Math.min(curMin, curValue.value());
            curMax = Math.max(curMax, curValue.value());
        }
        this.min = curMin;
        this.max = curMax;
        this.indicator = indicator;
    }

    public String getType() {
        return type;
    }

    public LocalDateTime getFrom() {
        return from;
    }

    public LocalDateTime getUntil() {
        return until;
    }

    public int getCount() {
        return count;
    }

    public double getMin() {
        return min;
    }

    public double getMax() {
        return max;
    }

    public double getIndicator() {
        return indicator;
    }

    /**
     * Converts the anomaly into a map for the REST response.
     *
     * @return Map with the anomalies attributes
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", this.type);
        map.put("from", this.from.toString());
        map.put("until", this.until.toString());
        map.put("count", this.count);
        map.put("min", this.min);
        map.put("max", this.max);
        map.put("indicator", this.indicator);
        return map;
    }

    @Override
    public String toString() {
        return this.type + " " + this.from + " - " + this.until + " (" + this.count + " values)";
    }
}
