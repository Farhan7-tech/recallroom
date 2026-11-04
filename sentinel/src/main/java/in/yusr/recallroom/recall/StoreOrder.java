package in.yusr.recallroom.recall;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import in.yusr.recallroom.feeds.BatchKeys;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** What one store has to pull off its shelves for one recall, and how far it has got. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class StoreOrder {

    public enum Status { WAITING_APPROVAL, ORDERED, CONFIRMED, RECHECK }

    public String store;
    public String storeName;
    public String room;
    /** The room the order was sent to; reports about this store count only from there. */
    public String roomId;
    public Status status = Status.WAITING_APPROVAL;
    public List<Line> lines = new ArrayList<>();
    public Instant orderedAt;
    public Instant confirmedAt;
    public String lastReport;
    /** Batches the store runner has checked on a shelf photo, as written in the order. */
    public Set<String> confirmedBatches = new LinkedHashSet<>();

    public int units() {
        return lines.stream().mapToInt(Line::qty).sum();
    }

    /**
     * Order lines whose batch number a report names as a whole token. Tokens split on spaces and
     * punctuation but keep '-' and '/', which batch numbers use, and are compared as strict keys for
     * equality: APRCT-2501 and APRCT-25O1 (a letter O) stay different batches, each needing its own
     * photo, and "CHPLT13460" never counts as CHPLT1346.
     */
    public List<String> batchesIn(String report) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : report.split("[^A-Za-z0-9/-]+")) {
            String key = BatchKeys.strict(token);
            if (!key.isEmpty()) {
                tokens.add(key);
            }
        }
        return lines.stream().map(Line::batch)
                .filter(b -> tokens.contains(BatchKeys.strict(b)))
                .distinct()
                .toList();
    }

    @JsonIgnore
    public boolean allBatchesConfirmed() {
        return lines.stream().map(Line::batch).allMatch(confirmedBatches::contains);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(String product, String batch, int qty, String unit, String shelf, String match, String flagSource, String reason) {
    }
}
