package in.yusr.recallroom.recall;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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

    public int units() {
        return lines.stream().mapToInt(Line::qty).sum();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(String product, String batch, int qty, String unit, String shelf, String match, String flagSource, String reason) {
    }
}
