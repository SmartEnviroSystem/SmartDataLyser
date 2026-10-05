package de.smartdata.lyser.data;

import de.fhbielefeld.scl.logger.Logger;
import de.fhbielefeld.scl.logger.message.Message;
import de.fhbielefeld.scl.logger.message.MessageLevel;
import de.fhbielefeld.scl.rest.util.WebTargetCreator;
import de.smartdata.lyser.config.Configuration;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.json.stream.JsonParser;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import java.time.Instant;
import java.sql.Timestamp;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.Map;

/**
 * Methods for accessing a SmartData instance to get data. Aims to simplify the
 * access of SmartData from other Java programs
 *
 * @author Florian Fehring
 */
public class SmartDataAccessor {

    protected String jndi = null;
    protected DataSource ds = null;
    protected String smartdataRequest; // Contains last called URL

    /**
     * Geneal purpose accessor
     */
    public SmartDataAccessor() {

    }

    /**
     * Optimized SmartDataAccessor for a samrtdata instance
     *
     * @param smartdataurl URL to SmartData instance
     */
    public SmartDataAccessor(String smartdataurl) {
        // Get SmartData instance name
        int lastSlash = smartdataurl.lastIndexOf("/");
        String smartdataname = smartdataurl;
        if (lastSlash >= 0) {
            smartdataname = smartdataurl.substring(lastSlash + 1);
        }
        // Load configuration for instance
        Configuration conf = new Configuration(smartdataname);
        this.jndi = conf.getProperty("postgres.jndi");
        if (this.jndi == null) {
            this.jndi = "jdbc/SmartData";
        }
        try {
            InitialContext ctx = new InitialContext();
            this.ds = (DataSource) ctx.lookup(this.jndi);
        } catch (NamingException ex) {
            Message msg = new Message("", MessageLevel.ERROR, "Could not access connection pool: " + ex.getLocalizedMessage());
            Logger.addMessage(msg);
        }
    }

    public String getJndi() {
        return this.jndi;
    }

    public Connection getConnection() {
        if (this.ds == null) {
            return null;
        }
        try {
            return this.ds.getConnection();
        } catch (SQLException ex) {
            Message msg = new Message("", MessageLevel.ERROR, "Could not conntect to database: " + ex.getLocalizedMessage());
            Logger.addMessage(msg);
        }
        return null;
    }

    /**
     * Gets the number of available datasets
     *
     * @param smartdataurl SmartDatas URL
     * @param collection Collections name
     * @param storage Storages name
     * @param exact true if exact value is wanted
     *
     * @return Number of available datasets
     * @throws de.smartdata.lyser.data.SmartDataAccessorException
     */
    public int fetchCount(String smartdataurl, String collection, String storage, boolean exact) throws SmartDataAccessorException {
        return this.fetchCount(smartdataurl, collection, storage, null, null, null, exact);
    }

    /**
     * Gets the number of available datasets
     *
     * @param smartdataurl SmartDatas URL
     * @param collection Collections name
     * @param storage Storages name
     * @param dateattr Date values holding attribute name
     * @param start Startdate
     * @param end Enddate
     * @param exact true if exact value is wanted
     *
     * @return Number of available datasets
     * @throws de.smartdata.lyser.data.SmartDataAccessorException
     */
    public int fetchCount(String smartdataurl, String collection, String storage, String dateattr, LocalDateTime start, LocalDateTime end, boolean exact) throws SmartDataAccessorException {

        // Local direct db access
        Connection con = this.getConnection();
        if (con != null) {
            try {
                // SQL-Abfrage mit einem Platzhalter für die Tabelle
                String sql = "SELECT reltuples AS estimate FROM pg_class WHERE relname = '" + collection + "'";
                if (exact) {
                    sql = "SELECT COUNT(*) FROM \"" + storage + "\".\"" + collection + "\"";
                }
                PreparedStatement preparedStatement = con.prepareStatement(sql);

                // Abfrage ausführen
                ResultSet resultSet = preparedStatement.executeQuery();

                // Ergebnis verarbeiten
                int count = 0;
                if (resultSet.next()) {
                    count = resultSet.getInt(1);
                }
                resultSet.close();
                preparedStatement.close();
                return count;
            } catch (Exception ex) {
                throw new SmartDataAccessorException("Could not get data from >" + collection + "< an sql error occured: " + ex.getLocalizedMessage());
            } finally {
                try {
                    con.close();
                } catch (SQLException ex) {
                    throw new SmartDataAccessorException("Could not close db connection. Possible memory leak." + ex.getLocalizedMessage());
                }
            }
        }

        // Get information about file from SmartData
        WebTarget webTarget = WebTargetCreator.createWebTarget(
                smartdataurl + "/smartdata", "records")
                .path(collection)
                .queryParam("storage", storage)
                .queryParam("countonly", true);
        if (start != null && end != null) {
            webTarget = webTarget.queryParam("filter", dateattr + ",gt," + start);
            webTarget = webTarget.queryParam("filter", dateattr + ",lt," + end);
        }

        // Note request URI for documentation
        this.smartdataRequest = webTarget.getUri().toString();

        Response response = webTarget.request(MediaType.APPLICATION_JSON).get();
        String responseText = response.readEntity(String.class);
        if (Response.Status.OK.getStatusCode() == response.getStatus()) {
            JsonParser parser = Json.createParser(new StringReader(responseText));
            parser.next();
            JsonArray records = parser.getObject().getJsonArray("records");
            if (records == null) {
                throw new SmartDataAccessorException("Could not get data from >" + webTarget.getUri() + "< retuned no >records<");
            }
            JsonObject cobj = records.getJsonObject(0);
            if (cobj == null) {
                throw new SmartDataAccessorException("Could not get data from >" + webTarget.getUri() + "< retuned no >data<");
            }
            return cobj.getInt("count");
        }
        throw new SmartDataAccessorException("Could not access >" + webTarget.getUri() + "< returned status: " + response.getStatus());
    }

    /**
     * Gets the total number of available datasets across referenced tables. The
     * reference table names are read from a column in the given collection.
     *
     * @param smartdataurl SmartData URL
     * @param collection   Collection name that contains the reference column
     * @param storage      Schema name
     * @param dateattr     Date attribute (optional)
     * @param start        Start date (optional)
     * @param end          End date (optional)
     * @param exact        true if exact value is wanted
     * @param refColumn    Column containing referenced table names
     * @return Total number of datasets across all referenced tables
     * @throws SmartDataAccessorException if any SQL or access error occurs
     */
    public int fetchCount(String smartdataurl, String collection, String storage,
            String dateattr, LocalDateTime start, LocalDateTime end,
            boolean exact, String refColumn) throws SmartDataAccessorException {

        Connection con = this.getConnection();
        if (con == null) {
            throw new SmartDataAccessorException("No database connection available for fetchCount with reference.");
        }

        try {
            List<String> refTables = new ArrayList<>();
            String refSql = "SELECT DISTINCT \"" + refColumn + "\" FROM \"" + storage + "\".\"" + collection + "\"";
            try (PreparedStatement ps = con.prepareStatement(refSql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String refName = rs.getString(1);
                    if (refName != null && !refName.isBlank()) {
                        refTables.add(refName.trim());
                    }
                }
            }

            if (refTables.isEmpty()) {
                throw new SmartDataAccessorException("No referenced tables found in column >" + refColumn + "< of collection >" + collection + "<");
            }

            List<String> existingTables = new ArrayList<>();
            String checkSql = "SELECT tablename FROM pg_catalog.pg_tables WHERE schemaname = ?";
            try (PreparedStatement ps = con.prepareStatement(checkSql)) {
                ps.setString(1, storage);
                ResultSet rs = ps.executeQuery();
                List<String> dbTables = new ArrayList<>();
                while (rs.next()) {
                    dbTables.add(rs.getString("tablename"));
                }
                rs.close();

                for (String t : refTables) {
                    if (dbTables.contains(t)) {
                        existingTables.add(t);
                    } else {
                        System.out.println("[fetchCount] Skipping missing table: " + t);
                    }
                }
            }

            if (existingTables.isEmpty()) {
                throw new SmartDataAccessorException("No existing referenced tables found in schema >" + storage + "<");
            }

            int totalCount = 0;

            for (String table : existingTables) {
                String sql;
                if (exact) {
                    sql = "SELECT COUNT(*) FROM \"" + storage + "\".\"" + table + "\"";
                } else {
                    sql = "SELECT reltuples AS estimate FROM pg_class WHERE relname = '" + table + "'";
                }

                // Datumsfilter nur bei exact-Zählung
                if (exact && dateattr != null && start != null && end != null) {
                    sql = "SELECT COUNT(*) FROM \"" + storage + "\".\"" + table + "\" WHERE \"" + dateattr + "\" >= '" + start + "' AND \"" + dateattr + "\" <= '" + end + "'";
                }

                try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        totalCount += rs.getInt(1);
                    }
                }
            }

            return totalCount;

        } catch (SQLException ex) {
            throw new SmartDataAccessorException("SQL error while fetching count by reference: " + ex.getLocalizedMessage());
        } finally {
            try {
                con.close();
            } catch (SQLException ex) {
                throw new SmartDataAccessorException("Could not close DB connection. " + ex.getLocalizedMessage());
            }
        }
    }

    /**
     * Calculates the aritmethic mean from a column
     *
     * @param smartdataurl URL of smartdata (e.g.
     * http://localhost:8080/SmartData)
     * @param collection Collections name (Tablename)
     * @param storage Storage name (Schemaname)
     * @param dateattr Name of the attribute that holds date information
     * @param start Start date of datasets used for calculation
     * @param end End date of datasets used for calculation
     * @param column Name of column to calculate the median
     * @return Arithmetic mean value
     * @throws SmartDataAccessorException
     */
    public double fetchArithmeticMean(String smartdataurl, String collection, String storage, String dateattr, LocalDateTime start, LocalDateTime end, String column) throws SmartDataAccessorException {
        // If available use local direct db access
        Connection con = this.getConnection();
        if (con != null) {
            try {
                // SQL for arithmetic mean
                String sql = "SELECT AVG(" + column + ") AS average FROM \"" + storage + "\".\"" + collection + "\"";
                if (dateattr != null && start != null && end != null) {
                    sql += " WHERE " + dateattr + " >= '" + start + "' AND " + dateattr + " <= '" + end + "'";
                }
                PreparedStatement preparedStatement = con.prepareStatement(sql);
                ResultSet resultSet = preparedStatement.executeQuery();
                double average = 0.0;
                if (resultSet.next()) {
                    average = resultSet.getDouble(1);
                }
                resultSet.close();
                preparedStatement.close();
                return average;
            } catch (Exception ex) {
                throw new SmartDataAccessorException("Could not get data from >" + collection + "< an SQL error occurred: " + ex.getLocalizedMessage());
            } finally {
                try {
                    con.close();
                } catch (SQLException ex) {
                    throw new SmartDataAccessorException("Could not close DB connection. Possible memory leak." + ex.getLocalizedMessage());
                }
            }
        }

        // Use SmartData API and calculate arithmetic mean
        List<Double> values = new ArrayList<>();

        String includes = column;
        String order = column + ",DESC";
        // Get datasets
        JsonArray datasets = this.fetchData(smartdataurl, collection, storage, includes, null, dateattr, start, end, order, null);
        for (JsonNumber curVal : datasets.getValuesAs(JsonNumber.class)) {
            values.add(curVal.bigDecimalValue().doubleValue());
        }

        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        double average = values.isEmpty() ? 0.0 : sum / values.size();

        return average;
    }

    /**
     * Calculates the standard deviation from a column
     *
     * @param smartdataurl URL of smartdata (e.g.
     * http://localhost:8080/SmartData)
     * @param collection Collections name (Tablename)
     * @param storage Storage name (Schemaname)
     * @param dateattr Name of the attribute that holds date information
     * @param start Start date of datasets used for calculation
     * @param end End date of datasets used for calculation
     * @param column Name of column to calculate the median
     * @return Standard deviation value
     * @throws SmartDataAccessorException
     */
    public double fetchStdDviation(String smartdataurl, String collection, String storage, String dateattr, LocalDateTime start, LocalDateTime end, String column) throws SmartDataAccessorException {
        // If available use local direct db access
        Connection con = this.getConnection();
        if (con != null) {
            try {
                // SQL for standard deviation
                String sql = "SELECT STDDEV(" + column + ") AS stddev FROM \"" + storage + "\".\"" + collection + "\"";
                if (dateattr != null && start != null && end != null) {
                    sql += " WHERE " + dateattr + " >= '" + start + "' AND " + dateattr + " <= '" + end + "'";
                }
                PreparedStatement preparedStatement = con.prepareStatement(sql);
                ResultSet resultSet = preparedStatement.executeQuery();

                double stddev = 0.0;
                if (resultSet.next()) {
                    stddev = resultSet.getDouble(1);
                }
                resultSet.close();
                preparedStatement.close();
                return stddev;
            } catch (Exception ex) {
                throw new SmartDataAccessorException("Could not get data from >" + collection + "< an SQL error occurred: " + ex.getLocalizedMessage());
            } finally {
                try {
                    con.close();
                } catch (SQLException ex) {
                    throw new SmartDataAccessorException("Could not close DB connection. Possible memory leak." + ex.getLocalizedMessage());
                }
            }
        }

        // use SmartData API
        List<Double> values = new ArrayList<>();

        String includes = column;
        String order = column + ",DESC";
        // Get datasets
        JsonArray datasets = this.fetchData(smartdataurl, collection, storage, includes, null, dateattr, start, end, order, null);
        for (JsonNumber curVal : datasets.getValuesAs(JsonNumber.class)) {
            values.add(curVal.bigDecimalValue().doubleValue());
        }

        // Calculate standard deviation
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        double mean = values.isEmpty() ? 0.0 : sum / values.size();

        double varianceSum = 0.0;
        for (double value : values) {
            varianceSum += Math.pow(value - mean, 2);
        }
        double standardDeviation = values.isEmpty() ? 0.0 : Math.sqrt(varianceSum / values.size());

        return standardDeviation;
    }

    /**
     * Calculates the median from a column
     *
     * @param smartdataurl URL of smartdata (e.g.
     * http://localhost:8080/SmartData)
     * @param collection Collections name (Tablename)
     * @param storage Storage name (Schemaname)
     * @param dateattr Name of the attribute that holds date information
     * @param start Start date of datasets used for calculation
     * @param end End date of datasets used for calculation
     * @param column Name of column to calculate the median
     * @return Median value
     * @throws SmartDataAccessorException
     */
    public double fetchMedian(String smartdataurl, String collection, String storage, String dateattr, LocalDateTime start, LocalDateTime end, String column) throws SmartDataAccessorException {
        // Use local direct DB access if available
        Connection con = this.getConnection();
        if (con != null) {
            try {
                // Check if table exists and is not empty
                String countSql = "SELECT COUNT(*) FROM \"" + storage + "\".\"" + collection + "\"";
                try (PreparedStatement countStmt = con.prepareStatement(countSql); ResultSet countRs = countStmt.executeQuery()) {
                    if (countRs.next() && countRs.getInt(1) == 0) {
                        throw new SmartDataAccessorException(
                                "Database table \"" + storage + "\".\"" + collection + "\" is empty"
                        );
                    }
                }

                // Build median query
                StringBuilder sql = new StringBuilder();
                sql.append("SELECT PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY \"")
                        .append(column)
                        .append("\") AS median FROM \"")
                        .append(storage)
                        .append("\".\"")
                        .append(collection)
                        .append("\"");

                if (dateattr != null && start != null && end != null) {
                    sql.append(" WHERE \"").append(dateattr).append("\" >= ? AND \"")
                            .append(dateattr).append("\" < ?");
                }

                System.out.print(sql);

                try (PreparedStatement ps = con.prepareStatement(sql.toString())) {
                    // Bind date parameters if given
                    if (dateattr != null && start != null && end != null) {
                        ps.setTimestamp(1, Timestamp.valueOf(start));
                        ps.setTimestamp(2, Timestamp.valueOf(end));
                    }
                    System.out.print(Timestamp.valueOf(start));
                    System.out.print(Timestamp.valueOf(end));

                    try (ResultSet rs = ps.executeQuery()) {
                        double median = Double.NaN;
                        if (rs.next()) {
                            median = rs.getDouble("median");
                            if (rs.wasNull()) {
                                median = Double.NaN;
                            }
                        }
                        return median;
                    }
                }
            } catch (Exception ex) {
                throw new SmartDataAccessorException(
                        "Could not get data from >" + collection + "<. SQL error: " + ex.getLocalizedMessage()
                );
            } finally {
                try {
                    con.close();
                } catch (SQLException ex) {
                    throw new SmartDataAccessorException(
                            "Could not close DB connection. Possible memory leak: " + ex.getLocalizedMessage()
                    );
                }
            }
        }

        // Fallback: calculate median using SmartData API
        List<Double> values = new ArrayList<>();
        String includes = column;
        String order = column + ",DESC";

        JsonArray datasets = this.fetchData(smartdataurl, collection, storage, includes, null,
                dateattr, start, end, order, null);

        for (JsonNumber curVal : datasets.getValuesAs(JsonNumber.class)) {
            values.add(curVal.bigDecimalValue().doubleValue());
        }

        if (values.isEmpty()) {
            return Double.NaN;
        }

        int size = values.size();
        if (size % 2 == 0) {
            return (values.get(size / 2 - 1) + values.get(size / 2)) / 2.0;
        } else {
            return values.get(size / 2);
        }
    }

    /**
     *
     * Calculates the median from a column, optionally filtered by date range
     * and/or a specific column value
     *
     * @param smartdataurl URL of smartdata (e.g.
     * [http://localhost:8080/SmartData](http://localhost:8080/SmartData))
     * @param collection Collections name (Tablename)
     * @param storage Storage name (Schemaname)
     * @param dateattr Name of the attribute that holds date information
     * @param start Start date of datasets used for calculation
     * @param end End date of datasets used for calculation
     * @param column Name of column to calculate the median
     * @param filterColumn Optional column used to filter rows
     * @param filterValue Optional value that filterColumn must match
     * @return Median value
     * @throws SmartDataAccessorException if the table is empty, if the filter
     * matches no rows, or if a database/SQL error occurs
     */
    public double fetchMedian(String smartdataurl, String collection, String storage,
            String dateattr, LocalDateTime start, LocalDateTime end,
            String column, String filterColumn, String filterValue)
            throws SmartDataAccessorException {

        Connection con = this.getConnection();
        if (con != null) {
            try {
                // Set schema search path if a non-public schema is provided
                if (storage != null && !storage.isEmpty() && !storage.equals("public")) {
                    try (Statement stmt = con.createStatement()) {
                        stmt.execute("SET search_path TO \"" + storage + "\", public");
                    }
                }

                // Check if table exists and is not empty
                String countSql = "SELECT COUNT(*) FROM \"" + storage + "\".\"" + collection + "\"";
                try (PreparedStatement countStmt = con.prepareStatement(countSql); ResultSet countRs = countStmt.executeQuery()) {

                    if (countRs.next()) {
                        int rowCount = countRs.getInt(1);
                        if (rowCount == 0) {
                            throw new SmartDataAccessorException(
                                    "Database table \"" + storage + "\".\"" + collection + "\" is empty"
                            );
                        }
                    }
                }

                // Build median query
                StringBuilder sql = new StringBuilder();
                sql.append("SELECT PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY \"")
                        .append(column)
                        .append("\") AS median FROM \"")
                        .append(storage)
                        .append("\".\"")
                        .append(collection)
                        .append("\"");

                List<String> conditions = new ArrayList<>();

                // Optional date filter
                if (dateattr != null && start != null && end != null) {
                    conditions.add("\"" + dateattr + "\" >= ?");
                    conditions.add("\"" + dateattr + "\" < ?");
                }

                // Optional additional filter
                if (filterColumn != null && !filterColumn.isBlank()
                        && filterValue != null && !filterValue.isBlank()) {
                    // Check if filter matches any rows in the table
                    String filterCheckSql = "SELECT COUNT(*) AS count FROM \"" + storage + "\".\"" + collection + "\" WHERE \""
                            + filterColumn + "\" = ?";
                    try (PreparedStatement filterStmt = con.prepareStatement(filterCheckSql)) {
                        Object typedValue = convertStringToAppropriateType(filterValue);
                        if(typedValue.getClass().equals(LocalDateTime.class)) {
                            LocalDateTime datetimeValue = (LocalDateTime) typedValue;
                            Timestamp ts = Timestamp.valueOf(datetimeValue);
                            filterStmt.setTimestamp(1, ts);
                        } else {
                            filterStmt.setObject(1, typedValue);
                        }
                        
                        try (ResultSet rsFilter = filterStmt.executeQuery()) {
                            if (rsFilter.next()) {
                                int matchedRows = rsFilter.getInt(1);
                                if (matchedRows == 0) {
                                    throw new SmartDataAccessorException(
                                            "Filter " + filterColumn + "=" + filterValue + " did not match any entries"
                                    );
                                }
                            }
                        }
                    }

                    // Add the filter condition for the actual median query
                    conditions.add("\"" + filterColumn + "\" = ?");
                }
                if (!conditions.isEmpty()) {
                    sql.append(" WHERE ").append(String.join(" AND ", conditions));
                }

                try (PreparedStatement preparedStatement = con.prepareStatement(sql.toString())) {

                    int paramIndex = 1;

                    // Bind date parameters
                    if (dateattr != null && start != null && end != null) {
                        preparedStatement.setTimestamp(paramIndex++, Timestamp.valueOf(start));
                        preparedStatement.setTimestamp(paramIndex++, Timestamp.valueOf(end));
                    }

                    // Bind optional filter parameter
                    if (filterColumn != null && !filterColumn.isBlank()
                            && filterValue != null && !filterValue.isBlank()) {
                        Object typedValue = convertStringToAppropriateType(filterValue);
                        preparedStatement.setObject(paramIndex++, typedValue);
                    }

                    try (ResultSet resultSet = preparedStatement.executeQuery()) {
                        if (resultSet.next()) {
                            double median = resultSet.getDouble("median");
                            if (!resultSet.wasNull()) {
                                return median;
                            }
                        }
                    }
                }

                // No matching rows or only NULL values
                return Double.NaN;

            } catch (Exception ex) {
                throw new SmartDataAccessorException(
                        "Could not get data from >" + collection + "<. SQL error: " + ex.getLocalizedMessage()
                );
            } finally {
                try {
                    con.close();
                } catch (SQLException ex) {
                    throw new SmartDataAccessorException(
                            "Could not close DB connection. Possible resource leak: " + ex.getLocalizedMessage()
                    );
                }
            }
        }

        // Fallback: SmartData API
        List<Double> values = new ArrayList<>();
        String includes = column;

        List<String> filters = null;
        if (filterColumn != null && !filterColumn.isBlank()
                && filterValue != null && !filterValue.isBlank()) {
            filters = new ArrayList<>();
            filters.add(filterColumn + ":" + filterValue);
        }

        String order = column + ",DESC";

        JsonArray datasets = this.fetchData(
                smartdataurl, collection, storage,
                includes, filters, dateattr, start, end, order, null
        );

        for (JsonNumber curVal : datasets.getValuesAs(JsonNumber.class)) {
            values.add(curVal.bigDecimalValue().doubleValue());
        }

        if (values.isEmpty()) {
            return Double.NaN;
        }

        int size = values.size();
        if (size % 2 == 0) {
            return (values.get(size / 2 - 1) + values.get(size / 2)) / 2.0;
        } else {
            return values.get(size / 2);
        }
    }

    /**
     * Calculates the minimum from a column
     *
     * @param smartdataurl URL of smartdata (e.g.
     * http://localhost:8080/SmartData)
     * @param collection Collections name (Tablename)
     * @param storage Storage name (Schemaname)
     * @param dateattr Name of the attribute that holds date information
     * @param start Start date of datasets used for calculation
     * @param end End date of datasets used for calculation
     * @param column Name of column to calculate the median
     * @return Minimum value
     * @throws SmartDataAccessorException
     */
    public double fetchMin(String smartdataurl, String collection, String storage, String dateattr, LocalDateTime start, LocalDateTime end, String column) throws SmartDataAccessorException {

        // Local direct db access
        Connection con = this.getConnection();
        if (con != null) {
            try {
                // SQL-Abfrage mit einem Platzhalter für die Tabelle
                String sql = "SELECT MIN(" + column + ") FROM \"" + storage + "\".\"" + collection + "\"";
                if (dateattr != null && start != null && end != null) {
                    sql += " WHERE " + dateattr + " >= '" + start + "' AND " + dateattr + " <= '" + end + "'";
                } else if (dateattr != null && start != null) {
                    sql += " WHERE " + dateattr + " >= '" + start + "'";
                } else if (dateattr != null && end != null) {
                    sql += " WHERE " + dateattr + " <= '" + end + "'";
                }
                PreparedStatement preparedStatement = con.prepareStatement(sql);

                // Abfrage ausführen
                ResultSet resultSet = preparedStatement.executeQuery();

                // Ergebnis verarbeiten
                double count = 0;
                if (resultSet.next()) {
                    count = resultSet.getDouble(1);
                }
                resultSet.close();
                preparedStatement.close();
                return count;
            } catch (Exception ex) {
                throw new SmartDataAccessorException("Could not get data from >" + collection + "< an sql error occured: " + ex.getLocalizedMessage());
            } finally {
                try {
                    con.close();
                } catch (SQLException ex) {
                    throw new SmartDataAccessorException("Could not close db connection. Possible memory leak." + ex.getLocalizedMessage());
                }
            }
        }

        // Get information about file from SmartData
        WebTarget webTarget = WebTargetCreator.createWebTarget(
                smartdataurl + "/smartdata", "records")
                .path(collection)
                .queryParam("storage", storage)
                .queryParam("order", column + ",DESC")
                .queryParam("size", 1);
        if (start != null && end != null) {
            webTarget = webTarget.queryParam("filter", dateattr + ",gt," + start);
            webTarget = webTarget.queryParam("filter", dateattr + ",lt," + end);
        }

        // Note request URI for documentation
        this.smartdataRequest = webTarget.getUri().toString();

        Response response = webTarget.request(MediaType.APPLICATION_JSON).get();
        String responseText = response.readEntity(String.class);
        if (Response.Status.OK.getStatusCode() == response.getStatus()) {
            JsonParser parser = Json.createParser(new StringReader(responseText));
            parser.next();
            JsonArray records = parser.getObject().getJsonArray("records");
            if (records == null) {
                throw new SmartDataAccessorException("Could not get data from >" + webTarget.getUri() + "< retuned no >records<");
            }
            JsonObject cobj = records.getJsonObject(0);
            if (cobj == null) {
                throw new SmartDataAccessorException("Could not get data from >" + webTarget.getUri() + "< retuned no >data<");
            }
            return cobj.getInt("count");
        }
        throw new SmartDataAccessorException("Could not access >" + webTarget.getUri() + "< returned status: " + response.getStatus());

    }

    /**
     * Calculates the maximum from a column
     *
     * @param smartdataurl URL of smartdata (e.g.
     * http://localhost:8080/SmartData)
     * @param collection Collections name (Tablename)
     * @param storage Storage name (Schemaname)
     * @param dateattr Name of the attribute that holds date information
     * @param start Start date of datasets used for calculation
     * @param end End date of datasets used for calculation
     * @param column Name of column to calculate the median
     * @return Maximum value
     * @throws SmartDataAccessorException
     */
    public double fetchMax(String smartdataurl, String collection, String storage, String dateattr, LocalDateTime start, LocalDateTime end, String column) throws SmartDataAccessorException {

        // Local direct db access
        Connection con = this.getConnection();
        if (con != null) {
            try {
                // SQL-Abfrage mit einem Platzhalter für die Tabelle
                String sql = "SELECT MAX(" + column + ") FROM \"" + storage + "\".\"" + collection + "\"";
                if (dateattr != null && start != null && end != null) {
                    sql += " WHERE " + dateattr + " >= '" + start + "' AND " + dateattr + " <= '" + end + "'";
                } else if (dateattr != null && start != null) {
                    sql += " WHERE " + dateattr + " >= '" + start + "'";
                } else if (dateattr != null && end != null) {
                    sql += " WHERE " + dateattr + " <= '" + end + "'";
                }
                PreparedStatement preparedStatement = con.prepareStatement(sql);

                // Abfrage ausführen
                ResultSet resultSet = preparedStatement.executeQuery();

                // Ergebnis verarbeiten
                double count = 0;
                if (resultSet.next()) {
                    count = resultSet.getDouble(1);
                }
                resultSet.close();
                return count;
            } catch (Exception ex) {
                throw new SmartDataAccessorException("Could not get data from >" + collection + "< an sql error occured: " + ex.getLocalizedMessage());
            } finally {
                try {
                    con.close();
                } catch (SQLException ex) {
                    throw new SmartDataAccessorException("Could not close db connection. Possible memory leak." + ex.getLocalizedMessage());
                }
            }
        }

        // Get information about file from SmartData
        WebTarget webTarget = WebTargetCreator.createWebTarget(
                smartdataurl + "/smartdata", "records")
                .path(collection)
                .queryParam("storage", storage)
                .queryParam("order", column + ",ASC")
                .queryParam("size", 1);
        if (start != null && end != null) {
            webTarget = webTarget.queryParam("filter", dateattr + ",gt," + start);
            webTarget = webTarget.queryParam("filter", dateattr + ",lt," + end);
        }

        // Note request URI for documentation
        this.smartdataRequest = webTarget.getUri().toString();

        Response response = webTarget.request(MediaType.APPLICATION_JSON).get();
        String responseText = response.readEntity(String.class);
        if (Response.Status.OK.getStatusCode() == response.getStatus()) {
            JsonParser parser = Json.createParser(new StringReader(responseText));
            parser.next();
            JsonArray records = parser.getObject().getJsonArray("records");
            if (records == null) {
                throw new SmartDataAccessorException("Could not get data from >" + webTarget.getUri() + "< retuned no >records<");
            }
            JsonObject cobj = records.getJsonObject(0);
            if (cobj == null) {
                throw new SmartDataAccessorException("Could not get data from >" + webTarget.getUri() + "< retuned no >data<");
            }
            return cobj.getInt("count");
        }
        throw new SmartDataAccessorException("Could not access >" + webTarget.getUri() + "< returned status: " + response.getStatus());

    }

    /**
     * Fetches the minimum timestamp from a column
     *
     * @param smartdataurl URL of SmartData (e.g.
     * http://localhost:8080/SmartData)
     * @param collection Collection name (table)
     * @param storage Storage name (schema)
     * @param dateattr Name of the timestamp column
     * @return Minimum timestamp in the column
     * @throws SmartDataAccessorException
     */
    public LocalDateTime fetchMinTimestamp(String smartdataurl, String collection, String storage, String dateattr) throws SmartDataAccessorException {
        Connection con = this.getConnection();
        if (con != null) {
            try {
                // First, check if the table has any rows
                String countSql = "SELECT COUNT(*) FROM \"" + storage + "\".\"" + collection + "\"";
                try (PreparedStatement countStmt = con.prepareStatement(countSql); ResultSet countRs = countStmt.executeQuery()) {

                    if (countRs.next()) {
                        int rowCount = countRs.getInt(1);
                        if (rowCount == 0) {
                            throw new SmartDataAccessorException(
                                    "Database table \"" + storage + "\".\"" + collection + "\" is empty"
                            );
                        }
                    }
                }

                // SQL to fetch the minimum timestamp
                String sql = "SELECT MIN(\"" + dateattr + "\") AS min_ts FROM \"" + storage + "\".\"" + collection + "\"";
                try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {

                    if (rs.next()) {
                        Timestamp ts = rs.getTimestamp("min_ts");
                        if (ts != null) {
                            return ts.toLocalDateTime();
                        }
                    }
                    // This should not happen since table is not empty, but return null as fallback
                    return null;
                }

            } catch (SQLException ex) {
                throw new SmartDataAccessorException("Could not fetch minimum timestamp: " + ex.getLocalizedMessage());
            } finally {
                try {
                    con.close();
                } catch (SQLException ex) {
                    // ignore
                }
            }
        } else {
            throw new SmartDataAccessorException("No DB connection available.");
        }
    }

    /**
     * Fetches the maximum timestamp from a column
     *
     * @param smartdataurl URL of SmartData (e.g.
     * http://localhost:8080/SmartData)
     * @param collection Collection name (table)
     * @param storage Storage name (schema)
     * @param dateattr Name of the timestamp column
     * @param start Optional start filter
     * @param end Optional end filter
     * @return Maximum timestamp in the column
     * @throws SmartDataAccessorException
     */
    public LocalDateTime fetchMaxTimestamp(String smartdataurl, String collection, String storage, String dateattr) throws SmartDataAccessorException {
        Connection con = this.getConnection();
        if (con != null) {
            try {
                // First, check if the table has any rows
                String countSql = "SELECT COUNT(*) FROM \"" + storage + "\".\"" + collection + "\"";
                try (PreparedStatement countStmt = con.prepareStatement(countSql); ResultSet countRs = countStmt.executeQuery()) {

                    if (countRs.next()) {
                        int rowCount = countRs.getInt(1);
                        if (rowCount == 0) {
                            throw new SmartDataAccessorException(
                                    "Database table \"" + storage + "\".\"" + collection + "\" is empty"
                            );
                        }
                    }
                }

                // SQL to fetch the maximum timestamp
                String sql = "SELECT MAX(\"" + dateattr + "\") AS max_ts FROM \"" + storage + "\".\"" + collection + "\"";
                try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {

                    if (rs.next()) {
                        Timestamp ts = rs.getTimestamp("max_ts");
                        if (ts != null) {
                            return ts.toLocalDateTime();
                        }
                    }
                    // Should not happen since table is not empty, but return null as fallback
                    return null;
                }

            } catch (SQLException ex) {
                throw new SmartDataAccessorException("Could not fetch maximum timestamp: " + ex.getLocalizedMessage());
            } finally {
                try {
                    con.close();
                } catch (SQLException ex) {
                    // ignore
                }
            }
        } else {
            throw new SmartDataAccessorException("No DB connection available.");
        }
    }

    /**
     * Get data from the SmartData and return it as JSON
     *
     * @param smartdataurl SmartDatas URL
     * @param collection Collections name
     * @param storage Storages name
     * @param includes List of attributes that should be returned
     * @param filters Any filter statement accepted by SmartData
     * @param dateattr Attribute that stores date information (if start and end
     * should be used)
     * @param start Startdate to look at
     * @param end Enddate to look at
     * @param order Attribute name to order by
     * @param limit Number of datasets to fetch
     * @return JSON with available data
     * @throws de.smartdata.lyser.data.SmartDataAccessorException
     */
    public JsonArray fetchData(String smartdataurl, String collection,
            String storage, String includes, List<String> filters,
            String dateattr, LocalDateTime start, LocalDateTime end,
            String order, Long limit) throws SmartDataAccessorException {

        // Local direct db access
        Connection con = this.getConnection();
        if (con != null && filters == null) {
            if (includes == null) {
                includes = "*";
            }
            try {
                // SQL-Abfrage mit einem Platzhalter für die Tabelle
                String sql = "SELECT " + includes + " FROM \"" + storage + "\".\"" + collection + "\"";
                if (dateattr != null && start != null && end != null) {
                    sql += " WHERE " + dateattr + " >= '" + start + "' AND " + dateattr + " <= '" + end + "'";
                }
                if (order != null) {
                    sql += " ORDER BY " + order.replace(',', ' ');
                }
                if (limit != null) {
                    sql += " LIMIT " + limit;
                }
                JsonArrayBuilder newdataarr;
                // Abfrage ausführen
                try (PreparedStatement preparedStatement = con.prepareStatement(sql)) {
                    // Abfrage ausführen
                    ResultSet resultSet = preparedStatement.executeQuery();

                    // Ergebnis in eine Liste von Maps umwandeln
                    newdataarr = Json.createArrayBuilder();
                    while (resultSet.next()) {
                        JsonObjectBuilder newdataset = Json.createObjectBuilder();
                        for (int i = 1; i <= resultSet.getMetaData().getColumnCount(); i++) {
                            String colName = resultSet.getMetaData().getColumnName(i);
                            String colType = resultSet.getMetaData().getColumnTypeName(i);
                            Object val = resultSet.getObject(i);

                            // Null-Werte korrekt behandeln
                            if (val == null) {
                                newdataset.addNull(colName);
                                continue;
                            }

                            switch (colType) {
                                case "bool", "boolean" ->
                                    newdataset.add(colName, resultSet.getBoolean(i));
                                case "int", "int4" ->
                                    newdataset.add(colName, resultSet.getInt(i));
                                case "int8", "bigserial" ->
                                    newdataset.add(colName, resultSet.getLong(i));
                                case "float", "float4" ->
                                    newdataset.add(colName, resultSet.getFloat(i));
                                case "float8" ->
                                    newdataset.add(colName, resultSet.getDouble(i));
                                case "timestamp" -> {
                                    Date timestamp = resultSet.getTimestamp(i);
                                    if (timestamp != null) {
                                        newdataset.add(colName, timestamp.toString());
                                    }
                                }
                                case "date" -> {
                                    Date date = resultSet.getDate(i);
                                    if (date != null) {
                                        newdataset.add(colName, date.toString());
                                    }
                                }
                                case "varchar" -> {
                                    String str = resultSet.getString(i);
                                    if (str != null) {
                                        newdataset.add(colName, str);
                                    }
                                }
                                default ->
                                    System.out.println("Unsupported column type >" + colType + "< used.");
                            }
                        }
                        newdataarr.add(newdataset);
                    }
                    resultSet.close();
                }
                String json = newdataarr.build().toString();
                try (JsonReader reader = Json.createReader(new StringReader(json))) {
                    // Lese das JSON-Array
                    JsonArray records = reader.readArray();
                    return records;
                } catch (Exception e) {
                    System.err.println("Fehler beim Parsen des JSON-Strings: " + e.getMessage());
                }
            } catch (Exception ex) {
                throw new SmartDataAccessorException("Could not get data from >" + collection + "< an sql error occured: " + ex.getLocalizedMessage());
            } finally {
                try {
                    con.close();
                } catch (SQLException ex) {
                    throw new SmartDataAccessorException("Could not close db connection. Possible memory leak." + ex.getLocalizedMessage());
                }
            }
        } else if (con != null) {
            try {
                con.close();
            } catch (SQLException ex) {
                throw new SmartDataAccessorException("Could not close db connection. Possible memory leak." + ex.getLocalizedMessage());
            }
        }

        // Get information about file from SmartData
        WebTarget webTarget = WebTargetCreator.createWebTarget(smartdataurl + "/smartdata", "records", true)
                .path(collection)
                .queryParam("storage", storage);
        if (filters != null) {
            for (String curFilter : filters) {
                if (curFilter.contains("&filter=")) {
                    String[] subFilters = curFilter.split("&filter=");
                    for (String curSubFilter : subFilters) {
                        webTarget = webTarget.queryParam("filter", curSubFilter);
                    }
                } else {
                    webTarget = webTarget.queryParam("filter", curFilter);
                }
            }
        }
        if (includes != null) {
            webTarget = webTarget.queryParam("includes", includes);
        }
        if (start != null) {
            webTarget = webTarget.queryParam("filter", dateattr + ",gt," + start);
        }
        if (end != null) {
            webTarget = webTarget.queryParam("filter", dateattr + ",lt," + end);
        }
        if (order != null) {
            webTarget = webTarget.queryParam("order", order.replace(" ", ","));
        }
        if (limit != null) {
            webTarget = webTarget.queryParam("size", limit);
        }

        // Note request URI for documentation
        this.smartdataRequest = webTarget.getUri().toString();

        Response response = webTarget.request(MediaType.APPLICATION_JSON).get();
        String responseText = response.readEntity(String.class);
        if (Response.Status.OK.getStatusCode() == response.getStatus()) {
            JsonParser parser = Json.createParser(new StringReader(responseText));
            parser.next();
            JsonArray records = parser.getObject().getJsonArray("records");
            if (records == null) {
                throw new SmartDataAccessorException("Could not get data from >" + webTarget.getUri() + "< retuned no >records<");
            }
            return records;
        } else {
            throw new SmartDataAccessorException("Could not access >" + webTarget.getUri() + "< returned status: " + response.getStatus());
        }
    }

    /**
     * Gets a list of available collections on the smartdata
     *
     * @param smartdataurl URL to the SmartData instance
     * @param storage Name of the storage to look at
     * @return List of collection names
     * @throws de.smartdata.lyser.data.SmartDataAccessorException
     */
    public List<String> fetchCollectons(String smartdataurl, String storage) throws SmartDataAccessorException {
        // Get information about file from SmartData
        WebTarget webTarget = WebTargetCreator.createWebTarget(
                smartdataurl + "/smartdata", "storage")
                .path("getCollections")
                .queryParam("name", storage);

        // Note request URI for documentation
        this.smartdataRequest = webTarget.getUri().toString();

        Response response = webTarget.request(MediaType.APPLICATION_JSON).get();
        String responseText = response.readEntity(String.class);
        if (Response.Status.OK.getStatusCode() == response.getStatus()) {
            JsonParser parser = Json.createParser(new StringReader(responseText));
            parser.next();
            JsonArray list = parser.getObject().getJsonArray("list");
            if (list == null) {
                throw new SmartDataAccessorException("Could not get storages from >" + webTarget.getUri() + "< retuned no >list<");
            }
            List<String> collections = new ArrayList<>();
            for (JsonValue curVal : list) {
                collections.add(curVal.asJsonObject().getString("name"));
            }
            return collections;
        } else {
            throw new SmartDataAccessorException("Could not access >" + webTarget.getUri() + "< returned status: " + responseText);
        }
    }

    /**
     * Get the size of the collection in byte
     *
     * @param smartdataurl SmartDatas URL
     * @param collection Collections name
     * @param storage Storages name
     * @return bytes of sotrage useage (estimated)
     * @throws SmartDataAccessorException
     */
    public long fetchSize(String smartdataurl, String collection, String storage) throws SmartDataAccessorException {
        // Local direct db access
        Connection con = this.getConnection();
        if (con != null) {
            try {
                // SQL-Abfrage mit einem Platzhalter für die Tabelle
                String sql = "SELECT pg_relation_size('\"" + storage + "\".\"" + collection + "\"')";
                long bytes;
                // Abfrage ausführen
                try (PreparedStatement preparedStatement = con.prepareStatement(sql); ResultSet resultSet = preparedStatement.executeQuery()) {
                    // Ergebnis verarbeiten
                    bytes = 0L;
                    if (resultSet.next()) {
                        bytes = resultSet.getLong(1);
                    }
                }
                return bytes;
            } catch (Exception ex) {
                throw new SmartDataAccessorException("Could not get data from >" + collection + "< an sql error occured: " + ex.getLocalizedMessage());
            } finally {
                try {
                    con.close();
                } catch (SQLException ex) {
                    throw new SmartDataAccessorException("Could not close db connection. Possible memory leak." + ex.getLocalizedMessage());
                }
            }
        }

        throw new SmartDataAccessorException("Could not get size for >" + smartdataurl + "<: Size fetching is currently not supported for databases accessable only over SmartData.");
    }

    public String getSmartdataRequest() {
        return this.smartdataRequest;
    }

    /**
     * Updated data to the database
     *
     * @param smartdataurl URL of smartdata instance to use
     * @param collection Collection to write in
     * @param storage Storage to write in
     * @param dataSet Dataset to use for update (only containing updateable
     * attributes and id)
     * @throws SmartDataAccessorException
     */
    public void updateData(String smartdataurl, String collection, String storage, JsonObject dataSet) throws SmartDataAccessorException {
        de.ngi.logging.Logger.log();
        Connection con = this.getConnection();
        if (con != null) {
            StringBuilder updateQuery = new StringBuilder("UPDATE " + storage + "." + collection + " SET ");
            List<Object> values = new ArrayList<>();

            int index = 0;
            for (Map.Entry<String, JsonValue> curEntry : dataSet.entrySet()) {
                String column = curEntry.getKey();
                if (column.equals("id")) {
                    continue;
                }
                JsonValue value = curEntry.getValue();
                if (index > 0) {
                    updateQuery.append(", ");
                }
                updateQuery.append(column).append(" = ?");
                values.add(convertJsonValue(value)); // Umwandlung von JsonValue zu Java-Wert (z. B. String, Boolean, etc.)

                index++;
            }
            updateQuery.append(" WHERE id = ?");
            long setId = dataSet.getJsonNumber("id").longValue();

            String query = updateQuery.toString();

            try {
                PreparedStatement preparedStatement = con.prepareStatement(query);

                // Add data
                for (int i = 0; i < values.size(); i++) {
                    preparedStatement.setObject(i + 1, values.get(i));
                }
                // Set id
                preparedStatement.setObject(values.size() + 1, setId);

                int rowsInserted = preparedStatement.executeUpdate();
                if (rowsInserted > 0) {
                    de.ngi.logging.Logger.log("Succsessfull added dataset");
                } else {
                    de.ngi.logging.Logger.log("No new dataset created");
                }
            } catch (SQLException ex) {
                de.ngi.logging.Logger.log("Error saveing in database: " + ex.getLocalizedMessage());
                ex.printStackTrace();
                throw new SmartDataAccessorException("Could not create new dataset: " + ex.getLocalizedMessage());
            } finally {
                try {
                    con.close();
                } catch (SQLException ex) {
                    throw new SmartDataAccessorException("Could not close db connection. Possible memory leak." + ex.getLocalizedMessage());
                }
            }
        } else {
            throw new UnsupportedOperationException("Not implemented yet");
        }
    }

    private Object convertJsonValue(JsonValue jsonValue) {
        switch (jsonValue.getValueType()) {
            case STRING:
                String strValue = ((JsonString) jsonValue).getString();

                // Versuch, das Format ISO 8601 automatisch zu erkennen
                try {
                    Instant instant = Instant.parse(strValue); // z. B. "2025-07-04T07:45:00Z"
                    return Timestamp.from(instant); // korrekt für PostgreSQL
                } catch (DateTimeParseException e) {
                    // Kein gültiger Timestamp – bleibt String
                    return strValue;
                }
            case NUMBER:
                JsonNumber num = (JsonNumber) jsonValue;
                // Ganzzahl oder Fließkommazahl je nach Inhalt
                return num.isIntegral() ? num.longValue() : num.doubleValue();
            case TRUE:
                return true;
            case FALSE:
                return false;
            case NULL:
                return null;
            default:
                throw new IllegalArgumentException("Unsupported JSON type: " + jsonValue.getValueType());
        }
    }

    /**
     * Checks if a given table exists in the specified schema.
     */
    private boolean tableExists(Connection con, String schema, String table) {
        try (ResultSet rs = con.getMetaData().getTables(null, schema, table, null)) {
            return rs.next();
        } catch (SQLException e) {
            de.ngi.logging.Logger.log("Error checking table existence for " + schema + "." + table + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Calculates the mean from a single table column.
     *
     * @param smartdataurl URL of smartdata (e.g.
     * http://localhost:8080/SmartData)
     * @param collection Table name
     * @param storage Schema name
     * @param dateattr Name of the date attribute (optional)
     * @param start Start time (optional)
     * @param end End time (optional)
     * @param column Column to calculate the mean for
     * @return Mean value
     * @throws SmartDataAccessorException if any error occurs
     */
    public double fetchMean(String smartdataurl, String collection, String storage, String dateattr,
            LocalDateTime start, LocalDateTime end, String column)
            throws SmartDataAccessorException {

        Connection con = this.getConnection();
        if (con != null) {
            try {
                // SQL-Abfrage zur Berechnung des Durchschnitts
                String sql = "SELECT AVG(\"" + column + "\") AS mean FROM \"" + storage + "\".\"" + collection + "\"";
                if (dateattr != null && start != null && end != null) {
                    sql += " WHERE \"" + dateattr + "\" >= '" + start + "' AND \"" + dateattr + "\" <= '" + end + "'";
                }

                try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getDouble("mean");
                    }
                }
                return Double.NaN;
            } catch (Exception ex) {
                throw new SmartDataAccessorException(
                        "Could not get mean from >" + collection + "<: " + ex.getLocalizedMessage());
            } finally {
                try {
                    con.close();
                } catch (SQLException ex) {
                    throw new SmartDataAccessorException(
                            "Could not close db connection. Possible memory leak. " + ex.getLocalizedMessage());
                }
            }
        }

        // --- Fallback über SmartData REST API ---
        List<Double> values = new ArrayList<>();
        String includes = column;
        String order = column + ",ASC";
        JsonArray datasets = this.fetchData(smartdataurl, collection, storage, includes, null, dateattr, start, end, order, null);

        for (JsonNumber curVal : datasets.getValuesAs(JsonNumber.class)) {
            values.add(curVal.bigDecimalValue().doubleValue());
        }

        return values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    /**
     * Calculates the mean from a column across multiple referenced tables. Only
     * includes tables that actually exist in the database.
     *
     * @param smartdataurl URL of smartdata (e.g.
     * http://localhost:8080/SmartData)
     * @param collection Parent table containing reference column
     * @param storage Schema name
     * @param dateattr Name of the date attribute (optional)
     * @param start Start time (optional)
     * @param end End time (optional)
     * @param column Column to calculate the mean for
     * @param refColumn Column that holds referenced table names
     * @return Mean value over all referenced tables
     * @throws SmartDataAccessorException if any error occurs
     */
    public double fetchMean(String smartdataurl, String collection, String storage, String dateattr,
            LocalDateTime start, LocalDateTime end, String column, String refColumn)
            throws SmartDataAccessorException {

        Connection con = this.getConnection();
        if (con == null) {
            throw new SmartDataAccessorException("No database connection available for fetchMean with reference.");
        }

        try {
            List<String> refTables = new ArrayList<>();
            String refSql = "SELECT DISTINCT \"" + refColumn + "\" FROM \"" + storage + "\".\"" + collection + "\"";
            try (PreparedStatement ps = con.prepareStatement(refSql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String refName = rs.getString(1);
                    if (refName != null && !refName.isBlank()) {
                        refTables.add(refName.trim());
                    }
                }
            }

            if (refTables.isEmpty()) {
                throw new SmartDataAccessorException("No referenced tables found in column >" + refColumn + "<");
            }

            List<String> existingTables = new ArrayList<>();
            String checkSql = "SELECT tablename FROM pg_catalog.pg_tables WHERE schemaname = ?";
            try (PreparedStatement ps = con.prepareStatement(checkSql)) {
                ps.setString(1, storage);
                ResultSet rs = ps.executeQuery();
                List<String> dbTables = new ArrayList<>();
                while (rs.next()) {
                    dbTables.add(rs.getString("tablename"));
                }
                rs.close();

                for (String t : refTables) {
                    if (dbTables.contains(t)) {
                        existingTables.add(t);
                    } else {
                        System.out.println("[fetchMean] Skipping missing table: " + t);
                    }
                }
            }

            if (existingTables.isEmpty()) {
                throw new SmartDataAccessorException("No existing referenced tables found in schema >" + storage + "<");
            }

            StringBuilder unionSql = new StringBuilder();
            unionSql.append("SELECT AVG(\"").append(column).append("\") AS mean FROM (");

            for (int i = 0; i < existingTables.size(); i++) {
                String table = existingTables.get(i);
                if (i > 0) {
                    unionSql.append(" UNION ALL ");
                }
                unionSql.append("SELECT \"").append(column).append("\" FROM \"")
                        .append(storage).append("\".\"").append(table).append("\"");

                if (dateattr != null && start != null && end != null) {
                    unionSql.append(" WHERE \"").append(dateattr).append("\" >= '").append(start)
                            .append("' AND \"").append(dateattr).append("\" <= '").append(end).append("'");
                }
            }

            unionSql.append(") AS all_").append(column);

            try (PreparedStatement ps = con.prepareStatement(unionSql.toString()); ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble("mean");
                } else {
                    return Double.NaN;
                }
            }

        } catch (SQLException ex) {
            throw new SmartDataAccessorException("SQL error while calculating mean by reference: " + ex.getLocalizedMessage());
        } finally {
            try {
                con.close();
            } catch (SQLException ex) {
                throw new SmartDataAccessorException("Could not close DB connection. " + ex.getLocalizedMessage());
            }
        }
    }

    /**
     * Calculates the mean from a single table column with optional filtering.
     *
     * @param smartdataurl URL of smartdata (e.g.
     * http://localhost:8080/SmartData)
     * @param collection Table name
     * @param storage Schema name
     * @param dateattr Name of the date attribute (optional)
     * @param start Start time (optional)
     * @param end End time (optional)
     * @param column Column to calculate the mean for
     * @param filterColumn Column name to filter by (optional)
     * @param filterValue Value to filter for (optional)
     * @return Mean value
     * @throws SmartDataAccessorException if any error occurs
     */
    public double fetchMean(String smartdataurl, String collection, String storage, String dateattr,
            LocalDateTime start, LocalDateTime end, String column,
            String filterColumn, String filterValue)
            throws SmartDataAccessorException {

        Connection con = this.getConnection();
        if (con != null) {
            try {
                String sql = "SELECT AVG(\"" + column + "\") AS mean FROM \"" + storage + "\".\"" + collection + "\"";
                boolean hasWhere = false;

                if (dateattr != null && start != null && end != null) {
                    sql += " WHERE \"" + dateattr + "\" >= '" + start + "' AND \"" + dateattr + "\" <= '" + end + "'";
                    hasWhere = true;
                }

                if (filterColumn != null && filterValue != null) {
                    if (hasWhere) {
                        sql += " AND \"" + filterColumn + "\" = '" + filterValue + "'";
                    } else {
                        sql += " WHERE \"" + filterColumn + "\" = '" + filterValue + "'";
                    }
                }
System.out.println("SQL: " + sql);
                try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getDouble("mean");
                    }
                }
                return Double.NaN;
            } catch (Exception ex) {
                throw new SmartDataAccessorException(
                        "Could not get mean from >" + collection + "<: " + ex.getLocalizedMessage());
            } finally {
                try {
                    con.close();
                } catch (SQLException ex) {
                    throw new SmartDataAccessorException(
                            "Could not close db connection. Possible memory leak. " + ex.getLocalizedMessage());
                }
            }
        }

        // --- Fallback über SmartData REST API ---
        List<Double> values = new ArrayList<>();
        String includes = column;
        String order = column + ",ASC";

        List<String> filters = null;
        if (filterColumn != null && filterValue != null) {
            filters = new ArrayList<>();
            filters.add(filterColumn + ",eq," + filterValue);
        }

        JsonArray datasets = this.fetchData(
                smartdataurl,
                collection,
                storage,
                includes,
                filters,
                dateattr,
                start,
                end,
                order,
                null
        );

        for (JsonValue value : datasets) {
            JsonObject obj = value.asJsonObject();
            if (obj.containsKey(column)) {
                try {
                    JsonNumber num = obj.getJsonNumber(column);
                    if (num != null) {
                        values.add(num.doubleValue());
                    }
                } catch (Exception e) {
                    // Ignoriere ungültige Werte
                }
            }
        }

        return values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    /**
     * Gets the number of available datasets
     *
     * @param smartdataurl SmartDatas URL
     * @param collection Collections name
     * @param storage Storages name
     * @param dateattr Date values holding attribute name
     * @param start Startdate
     * @param end Enddate
     * @param exact true if exact value is wanted
     * @param filterColumn Column name to filter by (optional)
     * @param filterValue Value to filter for (optional)
     *
     * @return Number of available datasets
     * @throws de.smartdata.lyser.data.SmartDataAccessorException
     */
    public int fetchCount(String smartdataurl, String collection, String storage, String dateattr,
            LocalDateTime start, LocalDateTime end, boolean exact,
            String filterColumn, String filterValue) throws SmartDataAccessorException {

        // Local direct db access
        Connection con = this.getConnection();
        if (con != null && exact) {
            try {
                StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM \"");
                sql.append(storage).append("\".\"").append(collection).append("\"");

                List<Object> parameters = new ArrayList<>();

                // Add date filters if provided
                if (start != null && end != null) {
                    sql.append(" WHERE \"").append(dateattr).append("\" > ? AND \"")
                            .append(dateattr).append("\" < ?");
                    parameters.add(start);
                    parameters.add(end);
                }

                // Add additional filter if provided
                if (filterColumn != null && filterValue != null) {
                    if (parameters.isEmpty()) {
                        sql.append(" WHERE \"").append(filterColumn).append("\" = ?");
                    } else {
                        sql.append(" AND \"").append(filterColumn).append("\" = ?");
                    }
                    parameters.add(filterValue);
                }

                PreparedStatement preparedStatement = con.prepareStatement(sql.toString());

                // Set parameters
                for (int i = 0; i < parameters.size(); i++) {
                    preparedStatement.setObject(i + 1, parameters.get(i));
                }

                // Execute query
                ResultSet resultSet = preparedStatement.executeQuery();
                int count = 0;
                if (resultSet.next()) {
                    count = resultSet.getInt(1);
                }
                resultSet.close();
                preparedStatement.close();
                return count;
            } catch (Exception ex) {
                throw new SmartDataAccessorException("Could not get data from >" + collection
                        + "< an sql error occurred: " + ex.getLocalizedMessage());
            } finally {
                try {
                    con.close();
                } catch (SQLException ex) {
                    throw new SmartDataAccessorException("Could not close db connection. Possible memory leak."
                            + ex.getLocalizedMessage());
                }
            }
        }

        // Get information about file from SmartData (fallback or when exact=false)
        WebTarget webTarget = WebTargetCreator.createWebTarget(
                smartdataurl + "/smartdata", "records")
                .path(collection)
                .queryParam("storage", storage)
                .queryParam("countonly", true);

        // Add date filters if provided
        if (start != null && end != null) {
            webTarget = webTarget.queryParam("filter", dateattr + ",gt," + start);
            webTarget = webTarget.queryParam("filter", dateattr + ",lt," + end);
        }

        // Add additional filter if provided
        if (filterColumn != null && filterValue != null) {
            webTarget = webTarget.queryParam("filter", filterColumn + ",eq," + filterValue);
        }

        // Note request URI for documentation
        this.smartdataRequest = webTarget.getUri().toString();

        Response response = webTarget.request(MediaType.APPLICATION_JSON).get();
        String responseText = response.readEntity(String.class);

        if (Response.Status.OK.getStatusCode() == response.getStatus()) {
            try (JsonParser parser = Json.createParser(new StringReader(responseText))) {
                parser.next();
                JsonArray records = parser.getObject().getJsonArray("records");
                if (records == null) {
                    throw new SmartDataAccessorException("Could not get data from >"
                            + webTarget.getUri() + "< returned no >records<");
                }
                JsonObject cobj = records.getJsonObject(0);
                if (cobj == null) {
                    throw new SmartDataAccessorException("Could not get data from >"
                            + webTarget.getUri() + "< returned no >data<");
                }
                return cobj.getInt("count");
            }
        }

        throw new SmartDataAccessorException("Could not access >" + webTarget.getUri()
                + "< returned status: " + response.getStatus());
    }

    /**
     * Converts a string value to the most appropriate type for database
     * comparison. Tries long first (for bigint/bigserial), then double, then
     * boolean, otherwise keeps as string.
     */
    private Object convertStringToAppropriateType(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }

        String trimmed = value.trim();

        // Try long first (covers both int and bigint in PostgreSQL)
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            // Not a long, continue
        }

        // Try double/float
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException e) {
            // Not a double, continue
        }

        // Try boolean
        if (trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(trimmed);
        }

        // Try datetime
        try {
            return LocalDateTime.parse(trimmed);
        } catch (DateTimeParseException e) {
            // Not a datetime
        }
        // Keep as string
        return trimmed;
    }
}
