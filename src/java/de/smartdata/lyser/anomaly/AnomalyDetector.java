package de.smartdata.lyser.anomaly;

import java.util.List;
import java.util.Map;

/**
 * Algorithm that searches a time series for anomalies.
 *
 * Detectors work on plain values and know nothing about REST or SmartData,
 * so they can be reused and unit tested without a server.
 */
public interface AnomalyDetector {

    /**
     * Gets the name of the check, used as type of the found anomalies and as
     * name of the REST endpoint.
     *
     * @return Name of the check
     */
    String getType();

    /**
     * Searches the series for anomalies.
     *
     * @param series Values ordered by time
     * @return Found anomalies, ordered by time
     */
    List<Anomaly> detect(List<TimeValue> series);

    /**
     * Gets the parameters used by the last call of detect(). Parameters that
     * were not given are determined from the data and reported here, so users
     * can see which thresholds were applied.
     *
     * @return Parameter names and values
     */
    Map<String, Object> getUsedParameters();
}
