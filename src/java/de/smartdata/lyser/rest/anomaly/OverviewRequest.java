package de.smartdata.lyser.rest.anomaly;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request for the anomaly overview of several datasets (e.g. devices).
 */
@Schema(description = "Request body for the anomaly overview")
public class OverviewRequest {

    @Schema(description = "SmartData URL", example = "/SmartData")
    public String smartdataurl;
    @Schema(description = "Storage name", example = "smartmonitoring")
    public String storage;
    @Schema(description = "Start of the period, default 30 days before end", example = "2026-10-26T00:00")
    public String start;
    @Schema(description = "End of the period, default now", example = "2026-11-25T00:00")
    public String end;
    @Schema(description = "One row of the overview per entry")
    public List<Series> series;

    @Schema(description = "Dataset whose attributes are checked")
    public static class Series {

        @Schema(description = "Id of the dataset, returned with the row", example = "9")
        public Object id;
        @Schema(description = "Name shown for the row", example = "Example device")
        public String name;
        @Schema(description = "Collection holding the values", example = "exampledata_anomalies")
        public String collection;
        @Schema(description = "Filter for collections several datasets share", example = "oo_id,neoeq,9")
        public String filter;
        @Schema(description = "Date attribute", example = "ts")
        public String dateattribute;
        @Schema(description = "Attributes holding measured values, all are checked", example = "[\"temperature\", \"humidity\"]")
        public List<String> attributes;
    }
}
