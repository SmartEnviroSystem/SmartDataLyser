-- Test data for the anomaly detection (SmartDataLyser /anomaly endpoints).
--
-- Creates a smooth temperature curve for 2026 and 2027 with one value every 15 minutes
-- and plants three anomalies on the same days of every month, so every
-- 30 day period contains each of them:
--   5th,  12:00          spike to 60 degrees                 -> rateofchange
--   12th, 06:00 - 14:00  value stuck at 18.0                 -> flatline
--   20th, 09:00 - 12:00  jumping between 0 and 35 degrees    -> oscillation
-- humidity contains no anomalies and can be used for comparison.
--
-- Also registers the device "Example device Anomalies" so the data can be
-- selected in SmartMonitoringFrontend (Datenanalyse > Anomalien).
--
-- Run it against the SmartMonitoring database, e.g. smartmonitoring_test.
-- It can be run several times, the data is recreated each time.

CREATE TABLE IF NOT EXISTS smartmonitoring.exampledata_anomalies (
    id bigserial PRIMARY KEY,
    ts timestamp NOT NULL,
    temperature float8,
    humidity float8
);

TRUNCATE smartmonitoring.exampledata_anomalies RESTART IDENTITY;

INSERT INTO smartmonitoring.exampledata_anomalies (ts, temperature, humidity)
SELECT ts,
    CASE
        WHEN extract(day FROM ts) = 5 AND ts::time = '12:00' THEN 60
        WHEN extract(day FROM ts) = 12 AND ts::time BETWEEN '06:00' AND '14:00' THEN 18.0
        WHEN extract(day FROM ts) = 20 AND ts::time BETWEEN '09:00' AND '12:00'
            THEN CASE WHEN n % 2 = 0 THEN 0 ELSE 35 END
        ELSE round((15 + 8 * sin(2 * pi() * extract(epoch FROM ts) / 86400) + 0.3 * sin(n * 12.9898))::numeric, 2)
    END,
    round((60 + 15 * cos(2 * pi() * extract(epoch FROM ts) / 86400) + 0.5 * sin(n * 78.233))::numeric, 2)
FROM (
    SELECT ts, row_number() OVER (ORDER BY ts) AS n
    FROM generate_series('2026-01-01 00:00'::timestamp, '2027-12-31 23:45'::timestamp, interval '15 minutes') AS ts
) AS series;

-- ootype_id 3 ("Time series data") and parent_id 2 ("Example organisation") are the example
-- entries of the database shipped with SmartDeveloper. Adjust them for other databases.
INSERT INTO smartmonitoring.tbl_observedobject (name, description, data_collection, ootype_id, parent_id, measurerate)
SELECT 'Example device "Anomalies"',
    'Smooth temperature curve with planted anomalies for testing the anomaly detection',
    'exampledata_anomalies', 3, 2, 900
WHERE NOT EXISTS (
    SELECT 1 FROM smartmonitoring.tbl_observedobject WHERE data_collection = 'exampledata_anomalies'
);
