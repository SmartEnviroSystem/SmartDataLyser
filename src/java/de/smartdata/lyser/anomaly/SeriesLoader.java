package de.smartdata.lyser.anomaly;

import de.smartdata.lyser.data.SmartDataAccessor;
import de.smartdata.lyser.data.SmartDataAccessorException;
import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Loads one attribute of a collection as time series from SmartData.
 */
public class SeriesLoader {

    /**
     * Allowed attribute names. Names are passed on to SmartData and may end up
     * in SQL statements, so only plain identifiers are accepted.
     */
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private String smartdataRequest;
    private int skipped;

    /**
     * Checks if the name is a plain identifier.
     *
     * @param name Name of a collection or attribute
     * @return true if the name may be used
     */
    public static boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    /**
     * Loads the series.
     *
     * @param smartdataurl SmartDatas URL
     * @param storage Storage name
     * @param collection Collection name
     * @param filters Additional filters accepted by SmartData
     * @param dateattribute Attribute holding the timestamp
     * @param attribute Attribute holding the measured value
     * @param start Start of the period
     * @param end End of the period
     * @return Series ordered by time
     * @throws SmartDataAccessorException if SmartData could not be accessed
     */
    public List<TimeValue> load(String smartdataurl, String storage, String collection,
            List<String> filters, String dateattribute, String attribute,
            LocalDateTime start, LocalDateTime end) throws SmartDataAccessorException {
        SmartDataAccessor sda = new SmartDataAccessor();
        JsonArray data = sda.fetchData(smartdataurl, collection, storage,
                dateattribute + "," + attribute, filters, dateattribute,
                start, end, dateattribute + ",ASC", null);
        this.smartdataRequest = sda.getSmartdataRequest();
        return this.toSeries(data, dateattribute, attribute);
    }

    /**
     * Converts SmartData records into a series. Records without time or
     * numeric value are skipped and counted.
     *
     * @param data Records
     * @param dateattribute Attribute holding the timestamp
     * @param attribute Attribute holding the measured value
     * @return Series ordered by time
     */
    public List<TimeValue> toSeries(JsonArray data, String dateattribute, String attribute) {
        List<TimeValue> series = new ArrayList<>();
        this.skipped = 0;
        for (JsonValue curValue : data) {
            if (curValue.getValueType() != JsonValue.ValueType.OBJECT) {
                this.skipped++;
                continue;
            }
            JsonObject curSet = curValue.asJsonObject();
            LocalDateTime time = parseTime(curSet.get(dateattribute));
            Double value = parseNumber(curSet.get(attribute));
            if (time == null || value == null) {
                this.skipped++;
                continue;
            }
            series.add(new TimeValue(time, value));
        }
        series.sort(Comparator.comparing(TimeValue::time));
        return series;
    }

    /**
     * Parses a timestamp. Accepts ISO format from SmartDatas REST interface as
     * well as the database format with a space instead of the T.
     */
    static LocalDateTime parseTime(JsonValue value) {
        if (!(value instanceof JsonString)) {
            return null;
        }
        String text = ((JsonString) value).getString().trim().replace(' ', 'T');
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException ex) {
            try {
                return OffsetDateTime.parse(text).toLocalDateTime();
            } catch (DateTimeParseException ex2) {
                return null;
            }
        }
    }

    static Double parseNumber(JsonValue value) {
        if (value instanceof JsonNumber number) {
            return number.doubleValue();
        }
        if (value instanceof JsonString text) {
            try {
                return Double.valueOf(text.getString().trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    public String getSmartdataRequest() {
        return this.smartdataRequest;
    }

    /**
     * @return Number of records skipped by the last conversion
     */
    public int getSkipped() {
        return this.skipped;
    }
}
