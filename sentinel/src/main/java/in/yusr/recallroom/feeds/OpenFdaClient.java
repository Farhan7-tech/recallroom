package in.yusr.recallroom.feeds;

import in.yusr.recallroom.config.SentinelProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the US FDA's drug recall (enforcement report) feed from api.fda.gov. */
@Component
public class OpenFdaClient {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    /** Text after "Lot", "Lots", "Lot #", "Lot No." up to the next sentence-ish break. */
    private static final Pattern LOT_RUN = Pattern.compile("(?i)\\blots?\\b\\s*(?:#|no\\.?|numbers?)?\\s*:?\\s*([^.;\\]]+)");
    private static final Pattern TOKEN = Pattern.compile("[A-Z0-9][A-Z0-9-]{3,19}");
    private static final Pattern DATE_LIKE = Pattern.compile("\\d{1,2}-\\d{1,2}-\\d{2,4}|\\d{4}-\\d{2}(-\\d{2})?");

    private final RestClient http;
    private final JsonMapper json;
    private final String url;

    public OpenFdaClient(SentinelProperties props, RestClient.Builder builder, JsonMapper json) {
        this.http = builder.clone().defaultHeader("User-Agent", "RecallRoom/0.1").build();
        this.json = json;
        this.url = props.feeds().openfdaUrl();
    }

    /** Drug recalls reported in the last {@code days} days, one entry per lot. */
    public List<FlaggedBatch> fetchRecent(int days) {
        LocalDate to = LocalDate.now();
        String range = "report_date:[" + to.minusDays(days).format(DAY) + "+TO+" + to.format(DAY) + "]";
        String body;
        try {
            body = http.get().uri(url + "?search=" + range + "&limit=100").retrieve().body(String.class);
        } catch (HttpClientErrorException.NotFound e) {
            return List.of(); // openFDA answers 404 when nothing matches
        }
        List<FlaggedBatch> out = new ArrayList<>();
        for (JsonNode r : json.readTree(body).path("results")) {
            String source = "openFDA " + r.path("recall_number").asString() + " (" + r.path("classification").asString() + ")";
            String product = r.path("product_description").asString();
            String reason = r.path("reason_for_recall").asString();
            String firm = r.path("recalling_firm").asString();
            String ref = "https://api.fda.gov/drug/enforcement.json?search=recall_number:" + r.path("recall_number").asString();
            for (String lot : lots(r.path("code_info").asString())) {
                out.add(new FlaggedBatch(source, shorten(product), lot, "", firm, reason, ref));
            }
        }
        return out;
    }

    /** Lot numbers out of FDA's free-text code_info ("Lot # 7717825A, Expiry: 11/30/2028."). */
    static Set<String> lots(String codeInfo) {
        Set<String> out = new LinkedHashSet<>();
        Matcher run = LOT_RUN.matcher(codeInfo == null ? "" : codeInfo);
        while (run.find()) {
            String text = run.group(1).replaceAll("(?i)\\bexp\\w*\\b.*", "");
            Matcher t = TOKEN.matcher(text.toUpperCase());
            while (t.find()) {
                String token = t.group();
                if (token.chars().anyMatch(Character::isDigit) && !DATE_LIKE.matcher(token).matches()) {
                    out.add(token);
                }
            }
        }
        return out;
    }

    private static String shorten(String product) {
        String p = product.replaceAll("\\s+", " ").trim();
        int comma = p.indexOf(',', 40);
        return comma > 0 ? p.substring(0, comma) : p;
    }
}
