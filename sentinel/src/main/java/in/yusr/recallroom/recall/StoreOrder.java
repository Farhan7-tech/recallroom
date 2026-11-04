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
     * Order lines whose batch number appears in a report. Compared as strict keys, so APRCT-2501 and
     * APRCT-25O1 (a letter O) stay different batches: each needs its own photo.
     */
    public List<String> batchesIn(String report) {
        String text = " " + report.toUpperCase().replaceAll("[^A-Z0-9]+", " ") + " ";
        return lines.stream().map(Line::batch)
                .filter(b -> text.contains(" " + BatchKeys.strict(b) + " ") || text.contains(" " + b.toUpperCase().replaceAll("[^A-Z0-9]+", " ") + " "))
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
