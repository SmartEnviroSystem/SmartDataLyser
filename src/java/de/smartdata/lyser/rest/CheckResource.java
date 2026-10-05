package de.smartdata.lyser.rest;

import de.fhbielefeld.scl.logger.Logger;
import de.fhbielefeld.scl.logger.LoggerException;
import de.fhbielefeld.scl.rest.util.ResponseObjectBuilder;
import de.fhbielefeld.smartuser.annotations.SmartUserAuth;
import de.smartdata.lyser.check.CompletenessChecker;
import de.smartdata.lyser.data.SmartDataAccessorException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import javax.naming.NamingException;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * REST interface for checking collections
 *
 * @author ffehring
 */
@Path("check")
@Tag(name = "Check", description = "Check data")
public class CheckResource implements Serializable {

    public CheckResource() {
        // Init logging
        try {
            String moduleName = (String) new javax.naming.InitialContext().lookup("java:module/ModuleName");
            Logger.getInstance("SmartDataLyser", moduleName);
            Logger.setDebugMode(true);
        } catch (LoggerException | NamingException ex) {
            System.err.println("Error init logger: " + ex.getLocalizedMessage());
        }
    }

    @GET
    @Path("completeness")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @SmartUserAuth
    @Operation(summary = "Completness",
            description = "Calculates completeness informations")
    @APIResponse(
            responseCode = "200",
            description = "Completeness informations")
    @APIResponse(
            responseCode = "404",
            description = "The collection could not be found")
    @APIResponse(
            responseCode = "500",
            description = "Internal error")
    public Response completeness(
            @Parameter(description = "SmartData URL") @QueryParam("smartdataurl") @DefaultValue("/SmartData") String smartdataurl,
            @Parameter(description = "Storage name") @QueryParam("storage") @DefaultValue("public") String storage,
            @Parameter(description = "Collection", required = true, example = "mycollection") @QueryParam("collection") String collection,
            @Parameter(description = "Any filter statement accepted by SmartData") @QueryParam("filter") List<String> filters,
            @Parameter(description = "Date attribute") @QueryParam("dateattribute") @DefaultValue("ts") String dateattribute,
            @Parameter(description = "Start date", example = "2020-12-24T18:00") @QueryParam("start") @DefaultValue("0001-01-01T00:00") String startDateStr,
            @Parameter(description = "End date", example = "2020-12-24T19:00") @QueryParam("end") @DefaultValue("9999-12-31T23:59:59") String endDateStr,
            @Parameter(description = "Measurement frequence (in seconds)") @QueryParam("measurefreq") @DefaultValue("10") Long measurefreq) {

        LocalDateTime startDate = parseDateTime(startDateStr, LocalDateTime.now().minusDays(30));
        LocalDateTime endDate = parseDateTime(endDateStr, LocalDateTime.now());

        ResponseObjectBuilder rob = new ResponseObjectBuilder();

        if (smartdataurl.startsWith("/")) {
            smartdataurl = "http://localhost:8080" + smartdataurl;
        }

        CompletenessChecker cc = new CompletenessChecker();
        try {
            rob = cc.checkTimeCompleteness(smartdataurl, collection, storage, filters, dateattribute, measurefreq, 10L, startDate, endDate);
        } catch (SmartDataAccessorException ex) {
            rob.setStatus(Response.Status.INTERNAL_SERVER_ERROR);
            rob.addErrorMessage("Could not check completeness: " + ex.getLocalizedMessage());
        }

        rob.setStatus(Response.Status.OK);
        return rob.toResponse();
    }

    @GET
    @Path("missingperiods")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @SmartUserAuth
    @Operation(summary = "Missing periods",
            description = "Calculates the periods where data is missing")
    @APIResponse(
            responseCode = "200",
            description = "List of periods with missing data")
    @APIResponse(
            responseCode = "404",
            description = "The collection could not be found")
    @APIResponse(
            responseCode = "500",
            description = "Internal error")
    public Response missingperiods(
            @Parameter(description = "SmartData URL") @QueryParam("smartdataurl") @DefaultValue("/SmartData") String smartdataurl,
            @Parameter(description = "Storage name") @QueryParam("storage") @DefaultValue("public") String storage,
            @Parameter(description = "Collection", required = true, example = "mycollection") @QueryParam("collection") String collection,
            @Parameter(description = "Any filter statement accepted by SmartData") @QueryParam("filter") List<String> filters,
            @Parameter(description = "Date attribute") @QueryParam("dateattribute") @DefaultValue("ts") String dateattribute,
            @Parameter(description = "Start date", example = "2020-12-24T18:00") @QueryParam("start") @DefaultValue("0001-01-01T00:00") String startDateStr,
            @Parameter(description = "End date", example = "2020-12-24T19:00") @QueryParam("end") @DefaultValue("9999-12-31T23:59:59") String endDateStr,
            @Parameter(description = "Measurement frequence (in seconds)") @QueryParam("measurefreq") @DefaultValue("10") Long measurefreq) {

        LocalDateTime startDate = parseDateTime(startDateStr, LocalDateTime.now().minusDays(30));
        LocalDateTime endDate = parseDateTime(endDateStr, LocalDateTime.now());

        ResponseObjectBuilder rob = new ResponseObjectBuilder();

        if (smartdataurl.startsWith("/")) {
            smartdataurl = "http://localhost:8080" + smartdataurl;
        }

        CompletenessChecker cc = new CompletenessChecker();
        try {
            rob.add(cc.checkMissingPeriods(smartdataurl, collection, storage, filters, dateattribute, measurefreq, 10L, startDate, endDate));
        } catch (SmartDataAccessorException ex) {
            rob.setStatus(Response.Status.INTERNAL_SERVER_ERROR);
            rob.addErrorMessage("Could not check completeness: " + ex.getLocalizedMessage());
        }
        if(cc.getSmartdataRequest() != null)
            rob.add("smartdataRequest", cc.getSmartdataRequest());
        rob.setStatus(Response.Status.OK);
        return rob.toResponse();
    }

    @GET
    @Path("hassetintime")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @SmartUserAuth
    @Operation(summary = "Has a new set",
            description = "Checks if the collection has a new set in the last x seconds.")
    @APIResponse(
            responseCode = "200",
            description = "List of periods with missing data")
    @APIResponse(
            responseCode = "404",
            description = "The collection could not be found")
    @APIResponse(
            responseCode = "500",
            description = "Internal error")
    public Response hasSetInTime(
            @Parameter(description = "SmartData URL") @QueryParam("smartdataurl") @DefaultValue("/SmartData") String smartdataurl,
            @Parameter(description = "Storage name") @QueryParam("storage") @DefaultValue("public") String storage,
            @Parameter(description = "Collection", required = true, example = "mycollection") @QueryParam("collection") String collection,
            @Parameter(description = "Any filter statement accepted by SmartData") @QueryParam("filter") List<String> filters,
            @Parameter(description = "Date attribute") @QueryParam("dateattribute") @DefaultValue("ts") String dateattribute,
            @Parameter(description = "Start date", example = "2020-12-24T18:00") @QueryParam("start") @DefaultValue("0001-01-01T00:00") String startDateStr,
            @Parameter(description = "End date", example = "2020-12-24T19:00") @QueryParam("end") @DefaultValue("9999-12-31T23:59:59") String endDateStr,
            @Parameter(description = "Past seconds", example = "60") @QueryParam("pastseconds") Long pastseconds) {

        LocalDateTime startDate = parseDateTime(startDateStr, LocalDateTime.now().minusDays(30));
        LocalDateTime endDate = parseDateTime(endDateStr, LocalDateTime.now());

        if (smartdataurl.startsWith("/")) {
            smartdataurl = "http://localhost:8080" + smartdataurl;
        }

        CompletenessChecker cc = new CompletenessChecker();
        try {
            ResponseObjectBuilder rob = cc.hasSetInTime(smartdataurl, collection, storage, filters, dateattribute, startDate, endDate);
            return rob.toResponse();
        } catch (SmartDataAccessorException ex) {
            ResponseObjectBuilder rob = new ResponseObjectBuilder();
            rob.setStatus(Response.Status.INTERNAL_SERVER_ERROR);
            rob.addErrorMessage("Could not check completeness: " + ex.getLocalizedMessage());
            return rob.toResponse();
        }
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