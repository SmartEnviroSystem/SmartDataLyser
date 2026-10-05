package de.smartdata.lyser.rest;

import de.fhbielefeld.scl.logger.Logger;
import de.fhbielefeld.scl.logger.LoggerException;
import de.fhbielefeld.scl.rest.util.ResponseListBuilder;
import de.fhbielefeld.scl.rest.util.ResponseObjectBuilder;
import de.fhbielefeld.smartuser.annotations.SmartUserAuth;
import de.smartdata.lyser.aggregate.TemporalClusterMaker;
import de.smartdata.lyser.data.SmartDataAccessor;
import de.smartdata.lyser.data.SmartDataAccessorException;
import de.smartdata.lyser.rest.dataaggregation.ClusterRequest;
import de.smartdata.lyser.rest.dataaggregation.DatasourceConfig;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.naming.NamingException;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * REST interface for createing data aggregations
 *
 * @author ffehring
 */
@Path("aggregation")
@Tag(name = "Aggregation", description = "Aggregate data")
public class DataAggregationResource implements Serializable {

    // Stores last calculated values
    public static Map<String, ResponseObjectBuilder> cache_count = new HashMap();
    public static Map<String, ResponseObjectBuilder> cache_activeindex = new HashMap();

    public DataAggregationResource() {
        // Init logging
        try {
            String moduleName = (String) new javax.naming.InitialContext().lookup("java:module/ModuleName");
            Logger.getInstance("SmartDataLyser", moduleName);
            Logger.setDebugMode(true);
        } catch (LoggerException | NamingException ex) {
            System.err.println("Error init logger: " + ex.getLocalizedMessage());
        }
    }

    @POST
    @Path("temporalcluster")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @SmartUserAuth
    @Operation(summary = "Temporal clusting",
            description = "Builds temporal clusters from datasets that are in the same timeslot. Merges the found datasets into one dataset per time cluster at the median timepoint.")
    @APIResponse(
            responseCode = "200",
            description = "Count result")
    @APIResponse(
            responseCode = "404",
            description = "One of the collections could not be found")
    @APIResponse(
            responseCode = "500",
            description = "Internal error")
    public Response temporalcluster(ClusterRequest request) {

        ResponseObjectBuilder rob = new ResponseObjectBuilder();

        // Get algorithm start time
        Instant calcStartTime = Instant.now();

        // Check if timerange is given and parse it
        LocalDateTime startDate = LocalDateTime.of(1970, 1, 1, 0, 0);
        LocalDateTime endDate = null;
        try {
            if (request.start != null) {
                startDate = LocalDateTime.parse(request.start);
            }
        } catch (DateTimeParseException ex) {
            rob.addErrorMessage("Could not parse start date: " + ex.getLocalizedMessage());
            rob.setStatus(Response.Status.INTERNAL_SERVER_ERROR);
            return rob.toResponse();
        }
        try {
            if (request.end != null) {
                endDate = LocalDateTime.parse(request.end);
            }
        } catch (DateTimeParseException ex) {
            rob.addErrorMessage("Could not parse end date: " + ex.getLocalizedMessage());
            rob.setStatus(Response.Status.INTERNAL_SERVER_ERROR);
            return rob.toResponse();
        }

        // Set default values if needed
        if (request.traceability == null) {
            request.traceability = false;
        }
        if (request.cluster_seconds == null) {
            request.cluster_seconds = 30;
        }
        if (request.onlyclosedclusters == null) {
            request.onlyclosedclusters = true;
        }
        if (request.date_attr == null) {
            request.date_attr = "ts";
        }

        // Recalculate endpoint when only closed clusters should be build
        if (request.onlyclosedclusters) {
            ZoneId zone = ZoneId.systemDefault();
            Instant startInstant = startDate.atZone(zone).toInstant();
            Instant now = Instant.now();
            long elapsedSeconds = Duration.between(startInstant, now).getSeconds();
            long completedWindows = elapsedSeconds / request.cluster_seconds;
            Instant lastWindowClose = startInstant.plusSeconds(completedWindows * request.cluster_seconds);
            endDate = LocalDateTime.ofInstant(lastWindowClose, zone);
            rob.add("cluster_until", endDate);
        }

        JsonArrayBuilder dataBuilder = Json.createArrayBuilder();
        JsonArrayBuilder inputDataSetsUrlsBuilder = Json.createArrayBuilder();
        int inputDataSetsCount = 0;
        // Get data from each datasource
        for (DatasourceConfig curSource : request.datasources) {
            // Check and autocorrect values if possible
            if (curSource.smartdataurl == null) {
                rob.setStatus(Response.Status.BAD_REQUEST);
                rob.addErrorMessage("Parameter >smartdataurl< is missing.");
                return rob.toResponse();
            }

            if (curSource.smartdataurl.startsWith("/")) {
                curSource.smartdataurl = "http://localhost:8080" + curSource.smartdataurl;
            }

            if (curSource.collection == null) {
                rob.setStatus(Response.Status.BAD_REQUEST);
                rob.addErrorMessage("Parameter >collection< is missing.");
                return rob.toResponse();
            }

            if (curSource.dateattribute == null) {
                curSource.dateattribute = "ts";
            }
            if (curSource.order == null) {
                curSource.order = curSource.dateattribute + " ASC";
            }
            if (curSource.limit == null) {
                curSource.limit = 100L;
            }

            // SmartDataAccessor accesses the SmartData either by URL (smartdataurl) or if locally available by useing the JDBC-Resource defined in the given SmartData Instance
            JsonArray curData;
            SmartDataAccessor acc = new SmartDataAccessor(curSource.smartdataurl);

            try {
                curData = acc.fetchData(curSource.smartdataurl, curSource.collection, curSource.storage, curSource.includes, curSource.filter, request.date_attr, startDate, endDate, curSource.order, curSource.limit);
                inputDataSetsCount += curData.size();
                inputDataSetsUrlsBuilder.add(acc.getSmartdataRequest());
                // Check if there is data
                if (curData.isEmpty()) {
                    rob.addWarningMessage("There was no data for aggregation found from source >" + curSource.smartdataurl + "/" + curSource.collection + "<.");
                } else {
                    String sourceId = curSource.smartdataurl
                            + ";" + curSource.collection
                            + ";" + curSource.storage;

                    curData.forEach(json -> {
                        if (json instanceof JsonObject) {
                            JsonObject original = (JsonObject) json;
                            JsonObjectBuilder extended = Json.createObjectBuilder(original);
                            extended.add("source", sourceId);
                            dataBuilder.add(extended.build());
                        } else {
                            rob.addWarningMessage("Unexpected non-object element in dataset.");
                        }
                    });
                }
            } catch (SmartDataAccessorException ex) {
                rob.addErrorMessage("Error fetching data from source: " + ex.getLocalizedMessage());
                rob.setStatus(Response.Status.INTERNAL_SERVER_ERROR);
                return rob.toResponse();
            }
        }
        rob.add("inputDatasetsCount", inputDataSetsCount);
        rob.add("inputDatasetsUrls", inputDataSetsUrlsBuilder.build());

        JsonArray data = dataBuilder.build();

        // Create TemporalClusterer Object
        TemporalClusterMaker tcm = new TemporalClusterMaker();
        tcm.setMapping(request.mapping);
        JsonObject result = tcm.makeCluster(data, request.date_attr, request.cluster_seconds, request.traceability, request.onlyclosedclusters, calcStartTime);

        ResponseListBuilder sourceDatasets = new ResponseListBuilder();
        for (int i = 0; i < data.size(); i++) {
            JsonObject obj = data.getJsonObject(i);
            sourceDatasets.add(obj);

            // If marking sets is activated
            if (request.processed_attr != null) {
                try {
                    JsonObjectBuilder updateSet = Json.createObjectBuilder();
                    updateSet.add("id", obj.getJsonNumber("id").longValue());
                    updateSet.add(request.processed_attr, true);
                    String sourceId = obj.getString("source", null);
                    String[] sourceIdParts = sourceId.split(";");
                    SmartDataAccessor acc = new SmartDataAccessor(sourceIdParts[0]);
                    // Update with sourceIdParts[0] = SmartDataURL, sourceIdParts[1] = collection, sourceIdParts[2] = storage
                    acc.updateData(sourceIdParts[0], sourceIdParts[1], sourceIdParts[2], updateSet.build());
                } catch (SmartDataAccessorException ex) {
                    rob.addErrorMessage("Could not mark datasets as processed. Error: " + ex.getLocalizedMessage());
                }
            }
        }
        if (request.processed_attr != null) {
            rob.addWarningMessage("Used datasets were marked as processed by setting the attribute >" + request.processed_attr + "< to true.");
        }
        if (request.traceability) {
            rob.add("inputDatasets", sourceDatasets);
        }
        rob.add(result);
        rob.setStatus(Response.Status.OK);
        return rob.toResponse();
    }

    @POST
    @Path("intervallMedians")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @SmartUserAuth
    @Operation(summary = "Building medians in intervals",
            description = "Aggregate data from intervals and delivers datasets with the median value for each interval.")
    @APIResponse(responseCode = "200", description = "Datasets for each interval with median values")
    @APIResponse(responseCode = "400", description = "Bad request")
    @APIResponse(responseCode = "404", description = "One of the collections could not be found")
    @APIResponse(responseCode = "500", description = "Internal error")
    public Response intervallMedians(
            @Parameter(description = "SmartData URL") @QueryParam("smartdataurl") @DefaultValue("/SmartData") String smartdataurl,
            @Parameter(description = "Storage name") @QueryParam("storage") @DefaultValue("public") String storage,
            @Parameter(description = "Collection name", required = true, example = "device_overview") @QueryParam("collection") String collection,
            @Parameter(description = "Date attribute") @QueryParam("dateattribute") @DefaultValue("ts") String dateattribute,
            @Parameter(description = "Start date", example = "2020-12-24T18:00") @QueryParam("start") @DefaultValue("0001-01-01T00:00") String startDateStr,
            @Parameter(description = "End date", example = "2020-12-24T19:00") @QueryParam("end") @DefaultValue("9999-12-31T23:59:59") String endDateStr,
            @Parameter(description = "Column to calculate median from", required = true, example = "pm10_0")
            @QueryParam("column") String column,
            @Parameter(description = "Column to filter by (optional)", example = "id")
            @QueryParam("filterColumn") String filterColumn,
            @Parameter(description = "Value that filterColumn must have (optional)", example = "6")
            @QueryParam("filterValue") String filterValue,
            @Parameter(description = "Interval for aggregation (optional, overrides automatic selection). Allowed values: month, week, day, hour, minute", example = "week")
            @QueryParam("intervall") String intervall) {

        LocalDateTime startDate = parseDateTime(startDateStr, LocalDateTime.MIN);
        LocalDateTime endDate = parseDateTime(endDateStr, LocalDateTime.MAX);

        ResponseObjectBuilder rob = new ResponseObjectBuilder();

        // Validate required parameters
        if (smartdataurl == null || collection == null || column == null) {
            rob.setStatus(Response.Status.BAD_REQUEST);
            rob.addErrorMessage("smartdataurl, collection and column are required parameters.");
            return rob.toResponse();
        }

        // Auto-correct smartdataurl
        if (smartdataurl.startsWith("/")) {
            smartdataurl = "http://localhost:8080" + smartdataurl;
        }

        // Create SmartDataAccessor
        SmartDataAccessor acc = new SmartDataAccessor(smartdataurl);

        try {
            // Fetch earliest set date from db
            if (startDate.equals(LocalDateTime.MIN)) {
                startDate = acc.fetchMinTimestamp(smartdataurl, collection, storage, dateattribute);
            }

            // Fet latest set date from db
            if (endDate.equals(LocalDateTime.MAX)) {
                endDate = acc.fetchMaxTimestamp(smartdataurl, collection, storage, dateattribute);
            }

            // Validate that both dates were successfully determined
            if (startDate == null || endDate == null) {
                rob.setStatus(Response.Status.NOT_FOUND);
                rob.addErrorMessage("No data found in table " + collection + " for column " + dateattribute);
                return rob.toResponse();
            }

        } catch (SmartDataAccessorException ex) {
            rob.setStatus(Response.Status.NOT_FOUND);
            rob.addErrorMessage("Could not determine start/end dates: " + ex.getLocalizedMessage());
            return rob.toResponse();
        }

        // Determine interval unit (automatic or manual)
        ChronoUnit intervalUnit;
        if (intervall != null && !intervall.isBlank()) {
            intervalUnit = parseInterval(intervall);
            if (intervalUnit == null) {
                rob.setStatus(Response.Status.BAD_REQUEST);
                rob.addErrorMessage("Invalid intervall value. Allowed: month, week, day, hour, minute.");
                return rob.toResponse();
            }
        } else {
            intervalUnit = determineInterval(startDate, endDate);
        }

        // Build list of interval boundaries
        List<LocalDateTime> intervals = assembleIntervals(startDate, endDate, intervalUnit);

        JsonArrayBuilder resultBuilder = Json.createArrayBuilder();

        // Check if filter parameters are provided
        boolean hasFilter = (filterColumn != null && !filterColumn.isBlank()
                && filterValue != null && !filterValue.isBlank());

        // Loop through each consecutive pair of intervals
        for (int i = 0; i < intervals.size() - 1; i++) {
            LocalDateTime from = intervals.get(i);
            LocalDateTime to = intervals.get(i + 1);

            JsonObjectBuilder record = Json.createObjectBuilder();
            record.add("ts", from.toString());

            try {
                double median;
                if (hasFilter) {
                    median = acc.fetchMedian(smartdataurl, collection, storage, dateattribute,
                            from, to, column, filterColumn, filterValue);
                } else {
                    median = acc.fetchMedian(smartdataurl, collection, storage, dateattribute,
                            from, to, column);
                }

                // Check if median is valid (not NaN or infinite)
                if (Double.isFinite(median)) {
                    record.add(column, median);
                    resultBuilder.add(record.build());
                } else {
                    // Only emit warnings if no additional filter is applied
                    if (!hasFilter) {
                        rob.addWarningMessage(
                                "No valid median available for interval " + from + " - " + to
                        );
                    }
                }
            } catch (SmartDataAccessorException ex) {
                rob.addWarningMessage(ex.getLocalizedMessage());
                rob.setStatus(Response.Status.NOT_FOUND);
                return rob.toResponse();
            }
        }

        // Build response
        rob.add("interval", intervalUnit.toString().toLowerCase());
        rob.add("records", resultBuilder.build());
        rob.setStatus(Response.Status.OK);
        return rob.toResponse();
    }

    /**
     * Determines the appropriate time interval unit based on the absolute time
     * difference between the given start and end timestamps.
     */
    private ChronoUnit determineInterval(LocalDateTime start, LocalDateTime end) {
        long diffDays = Duration.between(start.toLocalDate().atStartOfDay(), end.toLocalDate().atStartOfDay()).toDays();
        long diffMonths = ChronoUnit.MONTHS.between(start.withDayOfMonth(1), end.withDayOfMonth(1));
        long diffYears = ChronoUnit.YEARS.between(start.withDayOfYear(1), end.withDayOfYear(1));
        long diffHours = Duration.between(start, end).toHours();

        // Period over one year → aggregate by month
        if (diffYears >= 1) {
            return ChronoUnit.MONTHS;
        }

        // Period over multiple months → aggregate by month
        if (diffMonths >= 1) {
            return ChronoUnit.MONTHS;
        }

        // Period over multiple calendar days → aggregate by day
        if (diffDays >= 1) {
            return ChronoUnit.DAYS;
        }

        // Period over multiple hours → aggregate by hour
        if (diffHours >= 1) {
            return ChronoUnit.HOURS;
        }

        // Period less than an hour → aggregate by minute
        return ChronoUnit.MINUTES;
    }

    /**
     * Maps a textual interval parameter to a ChronoUnit.
     */
    private ChronoUnit parseInterval(String intervall) {
        return switch (intervall.toLowerCase()) {
            case "month", "months" ->
                ChronoUnit.MONTHS;
            case "week", "weeks" ->
                ChronoUnit.WEEKS;
            case "day", "days" ->
                ChronoUnit.DAYS;
            case "hour", "hours" ->
                ChronoUnit.HOURS;
            case "minute", "minutes" ->
                ChronoUnit.MINUTES;
            default ->
                null;
        };
    }

    /**
     * Creates a list of consecutive time boundaries starting at the given start
     * timestamp and ending at the given end timestamp.
     */
    private List<LocalDateTime> assembleIntervals(LocalDateTime start, LocalDateTime end, ChronoUnit unit) {
        List<LocalDateTime> intervals = new ArrayList<>();

        if (unit == ChronoUnit.DAYS) {
            // Set start and end to calendar days at 00:00
            LocalDate currentDay = start.toLocalDate();
            LocalDate endDay = end.toLocalDate();

            while (!currentDay.isAfter(endDay)) {
                intervals.add(currentDay.atStartOfDay());
                currentDay = currentDay.plusDays(1);
            }
            // Add last boundary as end +1 day to cover the final day's range correctly
            intervals.add(endDay.plusDays(1).atStartOfDay());

        } else if (unit == ChronoUnit.HOURS || unit == ChronoUnit.MINUTES || unit == ChronoUnit.WEEKS || unit == ChronoUnit.MONTHS) {
            // Generate intervals for other units
            LocalDateTime current = start;
            while (!current.isAfter(end)) {
                intervals.add(current);
                current = current.plus(1, unit);
            }
            // Add one extra step to cover last partial interval
            intervals.add(end.plus(1, unit));

        } else {
            // Fallback: simply use start and end
            intervals.add(start);
            intervals.add(end);
        }

        return intervals;
    }

    /**
     * Parse datetime string to LocalDateTime
     * @param dateStr String representation of date
     * @param defaultValue Default value if parsing fails or string equals MIN/MAX indicators
     * @return Parsed LocalDateTime or default value
     */
    private LocalDateTime parseDateTime(String dateStr, LocalDateTime defaultValue) {
        if (dateStr == null || dateStr.isEmpty()) {
            return defaultValue;
        }

        if (dateStr.equals("0001-01-01T00:00")) {
            return LocalDateTime.MIN;
        }

        if (dateStr.equals("9999-12-31T23:59:59")) {
            return LocalDateTime.MAX;
        }

        try {
            return LocalDateTime.parse(dateStr);
        } catch (Exception e) {
            return defaultValue;
        }
    }
}