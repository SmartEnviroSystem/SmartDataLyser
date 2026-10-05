package de.smartdata.lyser.rest;

import de.fhbielefeld.scl.logger.Logger;
import de.fhbielefeld.scl.logger.LoggerException;
import de.fhbielefeld.scl.rest.util.ResponseObjectBuilder;
import de.fhbielefeld.smartuser.annotations.SmartUserAuth;
import de.smartdata.lyser.data.SmartDataAccessor;
import de.smartdata.lyser.data.SmartDataAccessorException;
import de.smartdata.lyser.distance.Distance;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.Serializable;
import java.io.StringReader;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import javax.naming.NamingException;
import org.eclipse.microprofile.openapi.annotations.Operation;
import static org.eclipse.microprofile.openapi.annotations.enums.SchemaType.STRING;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;

/**
 * REST interface for executeing imports
 *
 * @author ffehring
 */
@Path("geo")
@Tag(name = "Geo", description = "Provides geo calculations on spatial data.")
public class GeoResource implements Serializable {

    // Stores last calculated values
    public static Map<String, ResponseObjectBuilder> cache_count = new HashMap();
    public static Map<String, ResponseObjectBuilder> cache_activeindex = new HashMap();

    public GeoResource() {
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
    @Path("distance")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @SmartUserAuth
    @Operation(summary = "Distance",
            description = "Calculates the distance between all the datasets that are delivered")
    @APIResponse(
            responseCode = "200",
            description = "Distance from first to last point")
    @APIResponse(
            responseCode = "404",
            description = "Collection could not be found")
    @APIResponse(
            responseCode = "500",
            description = "Internal error")
    public Response distance(
            @Parameter(description = "SmartData URL") @QueryParam("smartdataurl") @DefaultValue("/SmartData") String smartdataurl,
            @Parameter(description = "Storage name") @QueryParam("storage") @DefaultValue("public") String storage,
            @Parameter(description = "Collections name", example = "col1") @QueryParam("collection") String collection,
            @Parameter(description = "Any filter statement accepted by SmartData") @QueryParam("filter") List<String> filters,
            @Parameter(description = "Date attribute") @QueryParam("dateattribute") @DefaultValue("ts") String dateattribute,
            @Parameter(description = "Start date", example = "2020-12-24T18:00") @QueryParam("start") @DefaultValue("0001-01-01T00:00") String startDateStr,
            @Parameter(description = "End date", example = "2020-12-24T19:00") @QueryParam("end") @DefaultValue("9999-12-31T23:59:59") String endDateStr,
            @Parameter(description = "Geo attribute", example = "point") @QueryParam("geoattr") @DefaultValue("pos") String geoattr) {

        LocalDateTime startDate = parseDateTime(startDateStr, LocalDateTime.now().minusDays(30));
        LocalDateTime endDate = parseDateTime(endDateStr, LocalDateTime.now());

        return getDistance(smartdataurl, collection, storage, filters, dateattribute, startDate, endDate, geoattr);
    }

    private Response getDistance(String smartdataurl, String collection, String storage, List<String> filters, String dateattr, LocalDateTime startDate, LocalDateTime endDate, String geoattr) {
        ResponseObjectBuilder rob = new ResponseObjectBuilder();

        SmartDataAccessor acc = new SmartDataAccessor();

        if (smartdataurl.startsWith("/")) {
            smartdataurl = "http://localhost:8080" + smartdataurl;
        }

        if (collection == null) {
            rob.setStatus(Response.Status.BAD_REQUEST);
            rob.addErrorMessage("Parameter >collection< is missing.");
            return rob.toResponse();
        }

        JsonArray data;

        try {
            data = acc.fetchData(smartdataurl, collection, storage, geoattr, filters, dateattr, startDate, endDate, dateattr, null);
        } catch (SmartDataAccessorException ex) {
            rob.setStatus(Response.Status.INTERNAL_SERVER_ERROR);
            rob.addErrorMessage("Could not calculate distance because of error: " + ex.getLocalizedMessage());
            return rob.toResponse();
        }

        double totalDistance = 0;
        Double prevLat = null;
        Double prevLng = null;

        // Walk trough sets
        for (JsonValue curObj : data) {
            JsonObject dateobj = curObj.asJsonObject();

            JsonObject geoobj = dateobj.getJsonObject(geoattr);
            if (geoobj == null) {
                continue;
            }

            JsonArray geoarr = geoobj.getJsonArray("coordinates");
            if (geoarr == null) {
                continue;
            }

            double lat = geoarr.getJsonNumber(0).doubleValue();
            double lng = geoarr.getJsonNumber(1).doubleValue();

            if (prevLat != null && prevLng != null) {
                totalDistance += Distance.calc(lat, lng, prevLat, prevLng);
            }
            prevLat = lat;
            prevLng = lng;
        }

        rob.add("totalKM", totalDistance);
        rob.setStatus(Response.Status.OK);
        return rob.toResponse();
    }

    @GET
    @Path("duration")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @SmartUserAuth
    @Operation(summary = "Duration",
            description = "Calculates the duration between all the datasets that are delivered")
    @APIResponse(
            responseCode = "200",
            description = "Duration from first to last point")
    @APIResponse(
            responseCode = "404",
            description = "Collection could not be found")
    @APIResponse(
            responseCode = "500",
            description = "Internal error")
    public Response duration(
            @Parameter(description = "SmartData URL") @QueryParam("smartdataurl") @DefaultValue("/SmartData") String smartdataurl,
            @Parameter(description = "Storage name") @QueryParam("storage") @DefaultValue("public") String storage,
            @Parameter(description = "Collections name", example = "col1") @QueryParam("collection") String collection,
            @Parameter(description = "Any filter statement accepted by SmartData") @QueryParam("filter") List<String> filters,
            @Parameter(description = "Date attribute") @QueryParam("dateattribute") @DefaultValue("ts") String dateattribute,
            @Parameter(description = "Start date", example = "2020-12-24T18:00") @QueryParam("start") @DefaultValue("0001-01-01T00:00") String startDateStr,
            @Parameter(description = "End date", example = "2020-12-24T19:00") @QueryParam("end") @DefaultValue("9999-12-31T23:59:59") String endDateStr,
            @Parameter(description = "Geo attribute", example = "point") @QueryParam("geoattr") @DefaultValue("pos") String geoattr) {

        LocalDateTime startDate = parseDateTime(startDateStr, LocalDateTime.now().minusDays(30));
        LocalDateTime endDate = parseDateTime(endDateStr, LocalDateTime.now());

        return getDuration(smartdataurl, collection, storage, filters, dateattribute, startDate, endDate, geoattr);
    }

    private Response getDuration(String smartdataurl, String collection, String storage, List<String> filters, String dateattr, LocalDateTime startDate, LocalDateTime endDate, String geoattr) {
        ResponseObjectBuilder rob = new ResponseObjectBuilder();

        SmartDataAccessor acc = new SmartDataAccessor();

        if (smartdataurl.startsWith("/")) {
            smartdataurl = "http://localhost:8080" + smartdataurl;
        }

        if (collection == null) {
            rob.setStatus(Response.Status.BAD_REQUEST);
            rob.addErrorMessage("Parameter >collection< is missing.");
            return rob.toResponse();
        }

        JsonArray data;

        try {
            data = acc.fetchData(smartdataurl, collection, storage, geoattr, filters, dateattr, startDate, endDate, dateattr + ",ASC", null);
        } catch (SmartDataAccessorException ex) {
            rob.setStatus(Response.Status.INTERNAL_SERVER_ERROR);
            rob.addErrorMessage("Could not calculate duration because of error: " + ex.getLocalizedMessage());
            return rob.toResponse();
        }

        if (data == null || data.isEmpty()) {
            rob.add("durationH", 0);
            rob.setStatus(Response.Status.OK);
            return rob.toResponse();
        }

        String firstTS = data.get(0).asJsonObject().getString("ts");
        String lastTS = data.get(data.size() - 1).asJsonObject().getString("ts");
        LocalDateTime first = LocalDateTime.parse(firstTS);
        LocalDateTime last = LocalDateTime.parse(lastTS);

        double duration = Duration.between(first, last).toSeconds() / 3600.0;

        rob.add("durationH", duration);
        rob.setStatus(Response.Status.OK);
        return rob.toResponse();
    }

    @GET
    @Path("speed")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @SmartUserAuth
    @Operation(summary = "Speed",
            description = "Calculates the average speed between all the datasets that are delivered")
    @APIResponse(
            responseCode = "200",
            description = "Average speed from first to last point")
    @APIResponse(
            responseCode = "404",
            description = "Collection could not be found")
    @APIResponse(
            responseCode = "500",
            description = "Internal error")
    public Response speed(
            @Parameter(description = "SmartData URL") @QueryParam("smartdataurl") @DefaultValue("/SmartData") String smartdataurl,
            @Parameter(description = "Storage name") @QueryParam("storage") @DefaultValue("public") String storage,
            @Parameter(description = "Collections name", example = "col1") @QueryParam("collection") String collection,
            @Parameter(description = "Any filter statement accepted by SmartData") @QueryParam("filter") List<String> filters,
            @Parameter(description = "Date attribute") @QueryParam("dateattribute") @DefaultValue("ts") String dateattribute,
            @Parameter(description = "Start date", example = "2020-12-24T18:00") @QueryParam("start") @DefaultValue("0001-01-01T00:00") String startDateStr,
            @Parameter(description = "End date", example = "2020-12-24T19:00") @QueryParam("end") @DefaultValue("9999-12-31T23:59:59") String endDateStr,
            @Parameter(description = "Geo attribute (default: pos)", example = "point") @QueryParam("geoattr") String geoattr) {

        LocalDateTime startDate = parseDateTime(startDateStr, LocalDateTime.now().minusDays(30));
        LocalDateTime endDate = parseDateTime(endDateStr, LocalDateTime.now());

        ResponseObjectBuilder rob = new ResponseObjectBuilder();

        Response respDist = getDistance(smartdataurl, collection, storage, filters, dateattribute, startDate, endDate, geoattr);

        if (respDist.getStatus() != 200) {
            return respDist;
        }

        String jsonDist = (String) respDist.getEntity();
        double totalDistance = Json
                .createReader(new StringReader(jsonDist))
                .readObject()
                .getJsonNumber("totalKM")
                .doubleValue();

        Response respDur = getDuration(smartdataurl, collection, storage, filters, dateattribute, startDate, endDate, geoattr);

        if (respDur.getStatus() != 200) {
            return respDur;
        }

        String jsonDur = (String) respDur.getEntity();
        double totalDuration = Json
                .createReader(new StringReader(jsonDur))
                .readObject()
                .getJsonNumber("durationH")
                .doubleValue();

        double speed;
        if (totalDuration == 0.0) {
            speed = 0.0;
        } else {
            speed = totalDistance / totalDuration;
        }

        rob.add("avgSpeedKMH", speed);
        rob.setStatus(Response.Status.OK);
        return rob.toResponse();
    }

    @GET
    @Path("altitudediff")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @SmartUserAuth
    @Operation(summary = "AltitudeDiff",
            description = "Calculates the altidude difference sum between all the datasets that are delivered")
    @APIResponse(
            responseCode = "200",
            description = "Altitude difference sum from first to last point")
    @APIResponse(
            responseCode = "404",
            description = "Collection could not be found")
    @APIResponse(
            responseCode = "500",
            description = "Internal error")
    public Response altitudediff(
            @Parameter(description = "SmartData URL") @QueryParam("smartdataurl") @DefaultValue("/SmartData") String smartdataurl,
            @Parameter(description = "Storage name") @QueryParam("storage") @DefaultValue("public") String storage,
            @Parameter(description = "Collections name", required = true, example = "col1") @QueryParam("collection") String collection,
            @Parameter(description = "Any filter statement accepted by SmartData") @QueryParam("filter") List<String> filters,
            @Parameter(description = "Date attribute") @QueryParam("dateattribute") @DefaultValue("ts") String dateattribute,
            @Parameter(description = "Start date", example = "2020-12-24T18:00") @QueryParam("start") @DefaultValue("0001-01-01T00:00") String startDateStr,
            @Parameter(description = "End date", example = "2020-12-24T19:00") @QueryParam("end") @DefaultValue("9999-12-31T23:59:59") String endDateStr,
            @Parameter(description = "Geo attribute", example = "altitude") @QueryParam("geoattr") @DefaultValue("pos_altitude") String geoattr) {

        LocalDateTime startDate = parseDateTime(startDateStr, LocalDateTime.now().minusDays(30));
        LocalDateTime endDate = parseDateTime(endDateStr, LocalDateTime.now());

        ResponseObjectBuilder rob = new ResponseObjectBuilder();

        if (smartdataurl.startsWith("/")) {
            smartdataurl = "http://localhost:8080" + smartdataurl;
        }

        if (collection == null) {
            rob.setStatus(Response.Status.BAD_REQUEST);
            rob.addErrorMessage("Parameter >collection< is missing.");
            return rob.toResponse();
        }

        JsonArray data;
        SmartDataAccessor acc = new SmartDataAccessor();
        try {
            data = acc.fetchData(smartdataurl, collection, storage, geoattr, filters, dateattribute, startDate, endDate, dateattribute, null);
        } catch (SmartDataAccessorException ex) {
            rob.setStatus(Response.Status.INTERNAL_SERVER_ERROR);
            rob.addErrorMessage("Could not calculate altitude difference because of error: " + ex.getLocalizedMessage());
            return rob.toResponse();
        }

        double totalAltitude = 0;
        Double prevAltitude = null;

        // Walk trough sets
        for (JsonValue curObj : data) {
            JsonObject dateobj = curObj.asJsonObject();

            if (dateobj.getJsonNumber(geoattr) == null) {
                continue;
            }

            Double altitude = dateobj.getJsonNumber(geoattr).doubleValue();

            if (prevAltitude != null) {
                double result = prevAltitude - altitude;
                if (result < 0) {
                    result *= -1;
                }
                totalAltitude += result;
            }
            prevAltitude = altitude;
        }

        rob.add("altitudeMeters", totalAltitude);
        rob.setStatus(Response.Status.OK);
        return rob.toResponse();
    }

    @GET
    @Path("neargeometries")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @SmartUserAuth
    @Operation(summary = "Near geometries",
            description = "Deliver all datasets, where the geometries withinin a distance of x meters or less from each other.")
    @APIResponse(
            responseCode = "200",
            description = "List of datasets with near geometries.")
    @APIResponse(
            responseCode = "404",
            description = "Collection could not be found")
    @APIResponse(
            responseCode = "500",
            description = "Internal error")
    public Response neargeometries(
            @Parameter(description = "SmartData URL") @QueryParam("smartdataurl") @DefaultValue("/SmartData") String smartdataurl,
            @Parameter(description = "Storage name for first table") @QueryParam("storage") @DefaultValue("public") String storage_1,
            @Parameter(description = "Collection 1 name", example = "col1") @QueryParam("collection1") String collection_1,
            @Parameter(description = "Geometry attribute 1 name", schema = @Schema(type = STRING, defaultValue = "geom")) @QueryParam("geomattr1") String geomattr_1,
            @Parameter(description = "Storage name") @QueryParam("storage") @DefaultValue("public") String storage_2,
            @Parameter(description = "Collection 2 name", example = "col1") @QueryParam("collection2") String collection_2,
            @Parameter(description = "Geometry attribute 2 name", schema = @Schema(type = STRING, defaultValue = "geom")) @QueryParam("geomattr2") String geomattr_2,
            @Parameter(description = "Maximum distance", schema = @Schema(type = STRING, defaultValue = "geom")) @QueryParam("distance") String distance) {

        ResponseObjectBuilder rob = new ResponseObjectBuilder();

        SmartDataAccessor acc = new SmartDataAccessor(smartdataurl);
        Connection con = acc.getConnection();
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        String result = "";
        try {
            String sql = "SELECT json_agg(json_build_object( 'a', a.id, 'b', b.id, 'dist', ST_Distance(ST_Transform(a.coordinates, 3857), ST_Transform(b.coordinates, 3857)) )) FROM \"" + storage_1 + "\".\"" + collection_1 + "\" a JOIN \"" + storage_2 + "\".\"" + collection_2 + "\" b ON ST_DWithin(ST_Transform(a." + geomattr_1 + ", 3857), ST_Transform(b." + geomattr_2 + ", 3857), " + distance + ") WHERE a.id < b.id;";
            pstmt = con.prepareStatement(sql);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                result = rs.getString(1);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            rob.setStatus(Response.Status.INTERNAL_SERVER_ERROR);
            rob.addErrorMessage("Could not recive data: " + e.getLocalizedMessage());
        } finally {
            try {
                if (rs != null) {
                    rs.close();
                }
                if (pstmt != null) {
                    pstmt.close();
                }
                if (con != null) {
                    con.close();
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }

        rob.add("list", result);
        rob.setStatus(Response.Status.OK);
        return rob.toResponse();
    }

    @GET
    @Path("triangulate")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @SmartUserAuth
    @Operation(summary = "Distance",
            description = "Calculates the distance between all the datasets that are delivered")
    @APIResponse(
            responseCode = "200",
            description = "Distance from first to last point")
    @APIResponse(
            responseCode = "404",
            description = "Collection could not be found")
    @APIResponse(
            responseCode = "500",
            description = "Internal error")
    public Response triangulate(
            @Parameter(description = "SmartData URL") @QueryParam("smartdataurl") @DefaultValue("/SmartData") String smartdataurl,
            @Parameter(description = "Collections name", example = "col1") @QueryParam("collection") String collection,
            @Parameter(description = "Storage name") @QueryParam("storage") @DefaultValue("public") String storage,
            @Parameter(description = "Any filter statement accepted by SmartData") @QueryParam("filter") List<String> filters,
            @Parameter(description = "Date attribute") @QueryParam("dateattribute") @DefaultValue("ts") String dateattribute,
            @Parameter(description = "Start date", example = "2020-12-24T18:00") @QueryParam("start") @DefaultValue("0001-01-01T00:00") String startDateStr,
            @Parameter(description = "End date", example = "2020-12-24T19:00") @QueryParam("end") @DefaultValue("9999-12-31T23:59:59") String endDateStr,
            @Parameter(description = "Geo attribute", example = "point") @QueryParam("geocolumn") String geoattribute) {

        LocalDateTime startDate = parseDateTime(startDateStr, LocalDateTime.now().minusDays(30));
        LocalDateTime endDate = parseDateTime(endDateStr, LocalDateTime.now());

        ResponseObjectBuilder rob = new ResponseObjectBuilder();

        SmartDataAccessor acc = new SmartDataAccessor();

        if (smartdataurl.startsWith("/")) {
            smartdataurl = "http://localhost:8080" + smartdataurl;
        }

        if (collection == null) {
            rob.setStatus(Response.Status.BAD_REQUEST);
            rob.addErrorMessage("Parameter >collection< is missing.");
            return rob.toResponse();
        }

        if (dateattribute == null) {
            dateattribute = "ts";
        }

        JsonArray data;
        try {
            data = acc.fetchData(smartdataurl, collection, storage, geoattribute, filters, dateattribute, startDate, endDate, dateattribute, null);
        } catch (SmartDataAccessorException ex) {
            rob.setStatus(Response.Status.INTERNAL_SERVER_ERROR);
            rob.addErrorMessage("Could not calculate distance because of error: " + ex.getLocalizedMessage());
            return rob.toResponse();
        }

        double totalDistance = 0;

        // Walk trough sets
        for (JsonValue curObj : data) {
            JsonObject datestr = curObj.asJsonObject();
            // Walk trough attributes
            for (Entry<String, JsonValue> curEntry : datestr.entrySet()) {
                System.out.println("TEST " + curEntry.getKey());
            }
            System.out.println("TEST " + datestr);
        }

        rob.add("total", totalDistance);
        rob.setStatus(Response.Status.OK);
        return rob.toResponse();
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