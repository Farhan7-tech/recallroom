package in.yusr.recallroom.recall;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One recall, from the feed drop that opened it to the last store confirming its shelves are clear. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Recall {

    public enum State {
        /** Alert posted; waiting for the matcher's impact table. */
        MATCHING,
        /** Stock is affected; waiting for the QA Head. */
        AWAITING_APPROVAL,
        /** Nothing on our shelves is affected. */
        NO_IMPACT,
        /** Stop-sale orders are out; waiting for stores to confirm. */
        IN_STORES,
        /** Every affected store has confirmed. */
        CLOSED
    }

    public String id;
    public Instant openedAt;
    public String source;
    public int flaggedCount;
    public String alertsFile;
    public State state = State.MATCHING;
    public String approvedBy;
    public String approvedById;
    public Instant approvedAt;
    public Map<String, StoreOrder> stores = new LinkedHashMap<>();
    public List<Event> timeline = new ArrayList<>();

    public void note(String text) {
        timeline.add(new Event(Instant.now(), text));
    }

    public long storesConfirmed() {
        return stores.values().stream().filter(s -> s.status == StoreOrder.Status.CONFIRMED).count();
    }

    public record Event(Instant at, String text) {
    }
}
