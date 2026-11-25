package de.smartdata.lyser.rest;

import de.fhbielefeld.scl.logger.Logger;
import de.fhbielefeld.scl.logger.LoggerException;
import de.fhbielefeld.scl.rest.util.ResponseObjectBuilder;
import de.fhbielefeld.smartuser.annotations.SmartUserAuth;
import de.smartdata.lyser.anomaly.Anomaly;
import de.smartdata.lyser.anomaly.AnomalyDetector;
import de.smartdata.lyser.anomaly.AnomalyOverview;
import de.smartdata.lyser.anomaly.FlatlineDetector;
import de.smartdata.lyser.anomaly.OscillationDetector;
import de.smartdata.lyser.anomaly.RateOfChangeDetector;
import de.smartdata.lyser.anomaly.SeriesLoader;
import de.smartdata.lyser.anomaly.SeriesStatistics;
import de.smartdata.lyser.anomaly.TimeValue;
import de.smartdata.lyser.data.SmartDataAccessorException;
import de.smartdata.lyser.rest.anomaly.OverviewRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.naming.NamingException;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * REST interface for detecting anomalies in time series.
 *
 * Every endpoint loads one attribute of a collection from SmartData and runs
 * one detector on it. Parameters that are not given are determined from the
 * data, the response reports the values that were used.
 */
@Path("anomaly")
@Tag(name = "Anomaly", description = "Detect anomalies in time series")
public class AnomalyResource implements Serializable {

    /**
     * Period analysed when no start is given.
     */
    private static final int DEFAULT_PERIOD_DAYS = 30;

    public AnomalyResource() {
        try {
            String moduleName = (String) new javax.naming.InitialContext().lookup("java:module/ModuleName");
            Logger.getInstance("SmartDataLyser", moduleName);
            Logger.setDebugMode(true);
        } catch (LoggerException | NamingException ex) {
            System.err.println("Error init logger: " + ex.getLocalizedMessage());
        }
    }

    @GET
    @Path("rateofchange")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @SmartUserAuth
    @Operation(summary = "Rate of change",
            description = "Finds values that change faster than plausible")
    @APIResponse(
            responseCode = "200",
            description = "Found anomalies and used parameters")
    @APIResponse(
            responseCode = "400",
            description = "Invalid parameters")
    @APIResponse(
            responseCode = "500",
            description = "Data could not be loaded")
    public Response rateofchange(
            @Parameter(description = "SmartData URL") @QueryParam("smartdataurl") @DefaultValue("/SmartData") String smartdataurl,
            @Parameter(description = "Storage name") @QueryParam("storage") @DefaultValue("public") String storage,
            @Parameter(description = "Collection", required = true, example = "mycollection") @QueryParam("collection") String collection,
            @Parameter(description = "Attribute holding the measured values", required = true, example = "temperature") @QueryParam("attribute") String attribute,
            @Parameter(description = "Any filter statement accepted by SmartData") @QueryParam("filter") List<String> filters,
            @Parameter(description = "Date attribute") @QueryParam("dateattribute") @DefaultValue("ts") String dateattribute,
            @Parameter(description = "Start date, default 30 days before end", example = "2020-12-24T18:00") @QueryParam("start") String startDateStr,
            @Parameter(description = "End date, default now", example = "2020-12-24T19:00") @QueryParam("end") String endDateStr,
            @Parameter(description = "Expected seconds between two values, determined from the data if not given") @QueryParam("measurefreq") Long measurefreq,
            @Parameter(description = "Largest allowed change per minute, determined from the data if not given") @QueryParam("maxdelta") Double maxdelta) {

        String error = this.checkPositive("measurefreq", measurefreq)
                + this.checkPositive("maxdelta", maxdelta);
        return this.analyse(new RateOfChangeDetector(maxdelta, measurefreq), error,
                smartdataurl, storage, collection, attribute, filters, dateattribute, startDateStr, endDateStr);
    }

    @GET
    @Path("flatline")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @SmartUserAuth
    @Operation(summary = "Flatline",
            description = "Finds periods in which the value stays constant for too long")
    @APIResponse(
            responseCode = "200",
            description = "Found anomalies and used parameters")
    @APIResponse(
            responseCode = "400",
            description = "Invalid parameters")
    @APIResponse(
            responseCode = "500",
            description = "Data could not be loaded")
    public Response flatline(
            @Parameter(description = "SmartData URL") @QueryParam("smartdataurl") @DefaultValue("/SmartData") String smartdataurl,
            @Parameter(description = "Storage name") @QueryParam("storage") @DefaultValue("public") String storage,
            @Parameter(description = "Collection", required = true, example = "mycollection") @QueryParam("collection") String collection,
            @Parameter(description = "Attribute holding the measured values", required = true, example = "temperature") @QueryParam("attribute") String attribute,
            @Parameter(description = "Any filter statement accepted by SmartData") @QueryParam("filter") List<String> filters,
            @Parameter(description = "Date attribute") @QueryParam("dateattribute") @DefaultValue("ts") String dateattribute,
            @Parameter(description = "Start date, default 30 days before end", example = "2020-12-24T18:00") @QueryParam("start") String startDateStr,
            @Parameter(description = "End date, default now", example = "2020-12-24T19:00") @QueryParam("end") String endDateStr,
            @Parameter(description = "Expected seconds between two values, determined from the data if not given") @QueryParam("measurefreq") Long measurefreq,
            @Parameter(description = "Minimum duration in seconds, determined from the data if not given") @QueryParam("minduration") Long minduration,
            @Parameter(description = "Allowed fluctuation, determined from the data if not given") @QueryParam("epsilon") Double epsilon) {

        String error = this.checkPositive("measurefreq", measurefreq)
                + this.checkPositive("minduration", minduration)
                + this.checkNotNegative("epsilon", epsilon);
        return this.analyse(new FlatlineDetector(minduration, epsilon, measurefreq), error,
                smartdataurl, storage, collection, attribute, filters, dateattribute, startDateStr, endDateStr);
    }

    @GET
    @Path("oscillation")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @SmartUserAuth
    @Operation(summary = "Oscillation",
            description = "Finds periods in which the value keeps jumping between extremes")
    @APIResponse(
            responseCode = "200",
            description = "Found anomalies and used parameters")
    @APIResponse(
            responseCode = "400",
            description = "Invalid parameters")
    @APIResponse(
            responseCode = "500",
            description = "Data could not be loaded")
    public Response oscillation(
            @Parameter(description = "SmartData URL") @QueryParam("smartdataurl") @DefaultValue("/SmartData") String smartdataurl,
            @Parameter(description = "Storage name") @QueryParam("storage") @DefaultValue("public") String storage,
            @Parameter(description = "Collection", required = true, example = "mycollection") @QueryParam("collection") String collection,
            @Parameter(description = "Attribute holding the measured values", required = true, example = "temperature") @QueryParam("attribute") String attribute,
            @Parameter(description = "Any filter statement accepted by SmartData") @QueryParam("filter") List<String> filters,
            @Parameter(description = "Date attribute") @QueryParam("dateattribute") @DefaultValue("ts") String dateattribute,
            @Parameter(description = "Start date, default 30 days before end", example = "2020-12-24T18:00") @QueryParam("start") String startDateStr,
            @Parameter(description = "End date, default now", example = "2020-12-24T19:00") @QueryParam("end") String endDateStr,
            @Parameter(description = "Number of values looked at together, default 10") @QueryParam("window") Integer window,
            @Parameter(description = "Minimum number of direction changes within the window, default 4") @QueryParam("minchanges") Integer minchanges,
            @Parameter(description = "Minimum step size in percent of the value range, default 50") @QueryParam("minamplitude") Double minamplitude) {

        String error = "";
        if (window != null && window < 3) {
            error += "Parameter >window< must be at least 3. ";
        }
        if (minchanges != null && minchanges < 1) {
            error += "Parameter >minchanges< must be at least 1. ";
        }
        if (minamplitude != null && (minamplitude <= 0 || minamplitude > 100)) {
            error += "Parameter >minamplitude< must be between 0 and 100. ";
        }
        return this.analyse(new OscillationDetector(window, minchanges, minamplitude), error,
                smartdataurl, storage, collection, attribute, filters, dateattribute, startDateStr, endDateStr);
    }

    @POST
    @Path("overview")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @SmartUserAuth
    @Operation(summary = "Overview",
            description = "Checks all given attributes of several datasets with all detectors and delivers the most important anomaly category per time bucket (the finest of minute, hour, day and week that gives at most 60 buckets and is not finer than the measure interval)")
    @APIResponse(
            responseCode = "200",
            description = "Buckets and one row per dataset")
    @APIResponse(
            responseCode = "400",
            description = "Invalid request")
    public Response overview(OverviewRequest request) {
        ResponseObjectBuilder rob = new ResponseObjectBuilder();
        String error = "";
        if (request == null || request.series == null || request.series.isEmpty()) {
            error += "The request needs at least one entry in >series<. ";
        } else {
            for (OverviewRequest.Series curSeries : request.series) {
                if (!SeriesLoader.isValidName(curSeries.collection))
                    error += "Collection >" + curSeries.collection + "< is missing or invalid. ";
                if (curSeries.dateattribute != null && !SeriesLoader.isValidName(curSeries.dateattribute))
                    error += "Date attribute >" + curSeries.dateattribute + "< is invalid. ";
                if (curSeries.attributes == null || curSeries.attributes.isEmpty()
                        || !curSeries.attributes.stream().allMatch(SeriesLoader::isValidName))
                    error += "Attributes of >" + curSeries.collection + "< are missing or invalid. ";
            }
        }
        LocalDateTime endDate = this.parseDateTime(request == null ? null : request.end, LocalDateTime.now());
        LocalDateTime startDate = this.parseDateTime(request == null ? null : request.start,
                endDate == null ? null : endDate.minusDays(DEFAULT_PERIOD_DAYS));
        error += this.checkPeriod(startDate, endDate);
        if (!error.isEmpty()) {
            rob.setStatus(Response.Status.BAD_REQUEST);
            rob.addErrorMessage(error.trim());
            return rob.toResponse();
        }

        String smartdataurl = request.smartdataurl == null ? "/SmartData" : request.smartdataurl;
        if (smartdataurl.startsWith("/")) {
            smartdataurl = "http://localhost:8080" + smartdataurl;
        }
        String storage = request.storage == null ? "public" : request.storage;

        // Analyse every series first, then choose the buckets: not finer than the
        // slowest measure interval, otherwise most buckets would hold no value
        List<Map<String, List<AnomalyOverview.Event>>> eventsPerRow = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        long measureInterval = 0;
        for (OverviewRequest.Series curSeries : request.series) {
            Map<String, List<AnomalyOverview.Event>> events = new LinkedHashMap<>();
            String rowError = null;
            try {
                Map<String, List<TimeValue>> series = new SeriesLoader().loadAll(smartdataurl, storage, curSeries.collection,
                        curSeries.filter == null ? List.of() : List.of(curSeries.filter),
                        curSeries.dateattribute == null ? "ts" : curSeries.dateattribute,
                        curSeries.attributes, startDate, endDate);
                for (Map.Entry<String, List<TimeValue>> curEntry : series.entrySet()) {
                    events.put(curEntry.getKey(), AnomalyOverview.analyse(curEntry.getValue()));
                    measureInterval = Math.max(measureInterval, SeriesStatistics.medianIntervalSeconds(curEntry.getValue()));
                }
            } catch (SmartDataAccessorException ex) {
                rowError = "Could not load data: " + ex.getLocalizedMessage();
            }
            eventsPerRow.add(events);
            errors.add(rowError);
        }

        AnomalyOverview overview = new AnomalyOverview(startDate, endDate, measureInterval);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < request.series.size(); rowIndex++) {
            OverviewRequest.Series curSeries = request.series.get(rowIndex);
            String[] cells = new String[overview.getBuckets().size()];
            String[] causes = new String[cells.length];
            Arrays.fill(cells, AnomalyOverview.NODATA);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", curSeries.id);
            row.put("name", curSeries.name);
            if (errors.get(rowIndex) != null)
                row.put("error", errors.get(rowIndex));
            for (Map.Entry<String, List<AnomalyOverview.Event>> curEntry : eventsPerRow.get(rowIndex).entrySet()) {
                String[] attributeCells = overview.project(curEntry.getValue());
                for (int i = 0; i < cells.length; i++) {
                    String worse = AnomalyOverview.worse(cells[i], attributeCells[i]);
                    if (!worse.equals(cells[i])) {
                        cells[i] = worse;
                        // The measured value to open in the detail view
                        causes[i] = AnomalyOverview.RANKING.indexOf(worse) > AnomalyOverview.RANKING.indexOf(AnomalyOverview.NORMAL)
                                ? curEntry.getKey() : null;
                    }
                }
            }
            List<Map<String, Object>> rowCells = new ArrayList<>();
            for (int i = 0; i < cells.length; i++) {
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("category", cells[i]);
                cell.put("attribute", causes[i]);
                rowCells.add(cell);
            }
            row.put("cells", rowCells);
            rows.add(row);
        }

        rob.add("start", startDate.toString());
        rob.add("end", endDate.toString());
        rob.add("bucket", overview.getBucketName());
        rob.add("buckets", overview.getBuckets().stream().map(LocalDateTime::toString).toList());
        rob.add("rows", rows);
        rob.setStatus(Response.Status.OK);
        return rob.toResponse();
    }

    /**
     * Loads the series and runs the detector on it.
     *
     * @param detector Detector to run
     * @param error Messages of failed parameter checks, empty if all are valid
     * @return Response with found anomalies
     */
    private Response analyse(AnomalyDetector detector, String error,
            String smartdataurl, String storage, String collection, String attribute,
            List<String> filters, String dateattribute, String startDateStr, String endDateStr) {
        ResponseObjectBuilder rob = new ResponseObjectBuilder();

        if (!SeriesLoader.isValidName(collection)) {
            error += "Parameter >collection< is missing or invalid. ";
        }
        if (!SeriesLoader.isValidName(attribute)) {
            error += "Parameter >attribute< is missing or invalid. ";
        }
        if (!SeriesLoader.isValidName(dateattribute)) {
            error += "Parameter >dateattribute< is invalid. ";
        }
        LocalDateTime endDate = this.parseDateTime(endDateStr, LocalDateTime.now());
        LocalDateTime startDate = this.parseDateTime(startDateStr, endDate == null ? null : endDate.minusDays(DEFAULT_PERIOD_DAYS));
        error += this.checkPeriod(startDate, endDate);
        if (!error.isEmpty()) {
            rob.setStatus(Response.Status.BAD_REQUEST);
            rob.addErrorMessage(error.trim());
            return rob.toResponse();
        }

        if (smartdataurl.startsWith("/")) {
            smartdataurl = "http://localhost:8080" + smartdataurl;
        }

        SeriesLoader loader = new SeriesLoader();
        List<TimeValue> series;
        try {
            series = loader.load(smartdataurl, storage, collection, filters, dateattribute, attribute, startDate, endDate);
        } catch (SmartDataAccessorException ex) {
            rob.setStatus(Response.Status.INTERNAL_SERVER_ERROR);
            rob.addErrorMessage("Could not load data: " + ex.getLocalizedMessage());
            return rob.toResponse();
        }

        List<Map<String, Object>> anomalies = new ArrayList<>();
        for (Anomaly curAnomaly : detector.detect(series)) {
            anomalies.add(curAnomaly.toMap());
        }

        rob.add("type", detector.getType());
        rob.add("collection", collection);
        rob.add("attribute", attribute);
        rob.add("start", startDate.toString());
        rob.add("end", endDate.toString());
        rob.add("values", series.size());
        rob.add("skipped", loader.getSkipped());
        rob.add("parameters", detector.getUsedParameters());
        rob.add("anomalies", anomalies);
        if (loader.getSmartdataRequest() != null) {
            rob.add("smartdataRequest", loader.getSmartdataRequest());
        }
        rob.setStatus(Response.Status.OK);
        return rob.toResponse();
    }

    private String checkPeriod(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null)
            return "Parameters >start< and >end< must have the format 2020-12-24T18:00. ";
        if (!start.isBefore(end))
            return "Parameter >start< must be before >end<. ";
        return "";
    }

    private String checkPositive(String name, Number value) {
        if (value != null && value.doubleValue() <= 0) {
            return "Parameter >" + name + "< must be greater than 0. ";
        }
        return "";
    }

    private String checkNotNegative(String name, Number value) {
        if (value != null && value.doubleValue() < 0) {
            return "Parameter >" + name + "< must not be negative. ";
        }
        return "";
    }

    /**
     * Parse datetime string to LocalDateTime
     *
     * @param dateStr String representation of date
     * @param defaultValue Value if no date is given
     * @return Parsed LocalDateTime, the default value or null if the string is
     * not a valid date
     */
    private LocalDateTime parseDateTime(String dateStr, LocalDateTime defaultValue) {
        if (dateStr == null || dateStr.isBlank()) {
            return defaultValue;
        }
        try {
            return LocalDateTime.parse(dateStr.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
