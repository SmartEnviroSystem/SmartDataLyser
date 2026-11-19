package de.smartdata.lyser.anomaly;

import java.time.LocalDateTime;

/**
 * One measured value of a time series.
 *
 * @param time Point in time of the measurement
 * @param value Measured value
 */
public record TimeValue(LocalDateTime time, double value) {

}
