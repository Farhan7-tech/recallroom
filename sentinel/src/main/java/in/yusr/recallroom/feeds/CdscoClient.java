package in.yusr.recallroom.feeds;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import in.yusr.recallroom.config.SentinelProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads CDSCO's public monthly lists of drug samples that failed testing: "Not of Standard
 * Quality" (NSQ) and spurious. The data behind cdscoonline.gov.in/CDSCO/viewPublicNSQDrug.
 */
@Component
public class CdscoClient {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM-yyyy", Locale.ENGLISH);
    private static final String PUBLIC_PAGE = "https://cdscoonline.gov.in/CDSCO/viewPublicNSQDrug";

    enum ListType {
        NSQ("nsq", "filteredNsqDrugTable"),
        SPURIOUS("spurious", "filteredSpuriousDrugTable");

        final String tab;
        final String endpoint;

        ListType(String tab, String endpoint) {
            this.tab = tab;
            this.endpoint = endpoint;
        }
    }

    private final RestClient http;
    private final JsonMapper json;

    public CdscoClient(SentinelProperties props, RestClient.Builder builder, JsonMapper json) {
        this.http = builder.clone()
                .baseUrl(props.feeds().cdscoBaseUrl())
                .defaultHeader("User-Agent", "RecallRoom/0.1 (pharmacy recall response)")
                .build();
        this.json = json;
    }

    /** Both lists for one month; empty when CDSCO has not published that month yet. */
    public List<FlaggedBatch> fetchMonth(YearMonth month) {
        List<FlaggedBatch> out = new ArrayList<>();
        for (ListType type : ListType.values()) {
            String label = "CDSCO " + (type == ListType.NSQ ? "NSQ" : "spurious") + " " + month.format(MONTH);
            for (Row row : fetch(month, type)) {
                if (row.batchNo() == null || row.batchNo().isBlank()) {
                    continue;
                }
                out.add(new FlaggedBatch(label, clean(row.productName()), clean(row.batchNo()),
                        clean(row.expiryDate()), firstPart(row.manufacturer()), clean(row.result()), PUBLIC_PAGE));
            }
        }
        return out;
    }

    private List<Row> fetch(YearMonth month, ListType type) {
        // The endpoint answers with JSON but labels it text/html, so parse the body ourselves.
        String body = http.get()
                .uri(uri -> uri.path("/" + type.endpoint)
                        .queryParam("month", month.format(MONTH))
                        .queryParam("source", "All")
                        .queryParam("tab", type.tab)
                        .build())
                .retrieve()
                .body(String.class);
        if (body == null || body.isBlank()) {
            return List.of();
        }
        Page page = json.readValue(body, Page.class);
        return page.aaData() == null ? List.of() : page.aaData();
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /** Manufacturer cells hold a full postal address; the name before the first comma is enough. */
    private static String firstPart(String s) {
        String c = clean(s);
        int comma = c.indexOf(',');
        return comma > 0 ? c.substring(0, comma).trim() : c;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Row(
            @JsonProperty("str_product_name") @JsonAlias("product_name_from_dtl") String productName,
            @JsonProperty("str_batch_no") String batchNo,
            @JsonProperty("dt_expiry_date") String expiryDate,
            @JsonProperty("str_manufactured_by") String manufacturer,
            @JsonProperty("str_nsq_result") String result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Page(List<Row> aaData) {
    }
}
