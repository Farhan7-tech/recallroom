package in.yusr.recallroom.recall;

import in.yusr.recallroom.config.SentinelProperties;
import in.yusr.recallroom.feeds.CdscoClient;
import in.yusr.recallroom.feeds.FlaggedBatch;
import in.yusr.recallroom.feeds.OpenFdaClient;
import in.yusr.recallroom.switchapi.RoomMessage;
import in.yusr.recallroom.switchapi.SwitchApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sentinel's half of the recall workflow. It reads feeds, opens recalls, hands matching to the
 * matcher agent, holds every stop-sale order until the QA Head approves, sends the orders into the
 * store rooms and keeps the {@code recall-log} document current. Everything it does is triggered
 * by a message in a Switch room, so people and agents drive it the same way.
 */
@Service
public class RecallService {

    private static final Logger log = LoggerFactory.getLogger(RecallService.class);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM-yyyy", Locale.ENGLISH);
    private static final String ID = "(R-\\d{8}-\\d+)";
    private static final Pattern APPROVE = Pattern.compile("(?i)\\bapprove\\s+" + ID);
    private static final Pattern MATCHED = Pattern.compile("(?i)\\bmatched\\s+" + ID);
    private static final Pattern REPORT = Pattern.compile("(?i)\\b(confirm(?:ed)?|recheck|mismatch)\\s+" + ID + "\\s+store=([\\w-]+)\\s*(.*)", Pattern.DOTALL);
    private static final Pattern SCAN = Pattern.compile("(?i)^\\s*(?:scan|check)(?:\\s+feeds?)?\\s*(.*)$", Pattern.DOTALL);
    private static final Pattern STATUS = Pattern.compile("(?i)^\\s*status\\b\\s*" + ID + "?");
    private static final Pattern MENTION = Pattern.compile("@[\\w.-]+");

    private final SentinelProperties props;
    private final SwitchApi api;
    private final RecallStore store;
    private final ApprovalGate gate;
    private final CdscoClient cdsco;
    private final OpenFdaClient openFda;
    private volatile String hqRoomId;
    private volatile String logDocumentId;
    private volatile Instant lastPoll = Instant.EPOCH;

    public RecallService(SentinelProperties props, SwitchApi api, RecallStore store, ApprovalGate gate,
                         CdscoClient cdsco, OpenFdaClient openFda) {
        this.props = props;
        this.api = api;
        this.store = store;
        this.gate = gate;
        this.cdsco = cdsco;
        this.openFda = openFda;
    }

    /** Runs on every (re)connect: find the HQ room and bind this connection to it for the log document. */
    public void onConnected() {
        findRoom(props.rooms().hq()).ifPresentOrElse(id -> {
            hqRoomId = id;
            api.op("connect_to_room", Map.of("room_id", id, "include_general_instructions", false));
            refreshLog();
        }, () -> log.warn("Sentinel is not in a room called {} yet; invite it there", props.rooms().hq()));
    }

    public void onMessage(RoomMessage m) {
        if (!m.addressed()) {
            return;
        }
        String text = MENTION.matcher(m.body()).replaceAll(" ").replaceAll("\\s+", " ").trim();
        Matcher match;
        if ((match = APPROVE.matcher(text)).find()) {
            approve(m, match.group(1).toUpperCase(Locale.ROOT));
        } else if ((match = MATCHED.matcher(text)).find()) {
            matched(m, match.group(1).toUpperCase(Locale.ROOT));
        } else if ((match = REPORT.matcher(text)).find()) {
            storeReport(m, match.group(2).toUpperCase(Locale.ROOT), match.group(3).toLowerCase(Locale.ROOT),
                    !match.group(1).toLowerCase(Locale.ROOT).startsWith("confirm"), match.group(4).trim());
        } else if ((match = SCAN.matcher(text)).find()) {
            scan(m.roomId(), match.group(1).trim(), true);
        } else if ((match = STATUS.matcher(text)).find()) {
            status(m.roomId(), match.group(1));
        } else if (text.toLowerCase(Locale.ROOT).startsWith("whoami")) {
            api.post(m.roomId(), m.senderName() + ", Switch knows you as `" + m.sender() + "`"
                    + (gate.isQaHead(m.sender()) ? " (the QA Head)." : ". That id is not set as a QA Head."));
        } else {
            api.post(m.roomId(), help());
        }
    }

    // ---- 1. a regulator flags batches -------------------------------------------------------

    /**
     * Opens a recall from a feed. {@code what} is empty or "latest" (newest CDSCO month), a month
     * such as "Sep-2026", or "openfda [days]". Asked for by a person, every flagged batch goes to the
     * matcher; on the automatic poll only batches it has not raised before do.
     */
    void scan(String roomId, String what, boolean everything) {
        String w = what.toLowerCase(Locale.ROOT);
        List<FlaggedBatch> batches;
        String source;
        if (w.startsWith("openfda")) {
            int days = parseInt(w.replace("openfda", "").trim(), 30);
            batches = openFda.fetchRecent(days);
            source = "openFDA drug recalls, last " + days + " days";
        } else {
            Optional<YearMonth> month = w.isEmpty() || w.equals("latest") ? latestCdscoMonth() : parseMonth(what);
            if (month.isEmpty()) {
                api.post(roomId, "I couldn't read a month in \"" + what + "\". Try `@" + props.agent().name() + " scan Sep-2026`, `scan latest` or `scan openfda 30`.");
                return;
            }
            batches = cdsco.fetchMonth(month.get());
            source = "CDSCO Not-of-Standard-Quality and spurious drug list, " + month.get().format(MONTH);
        }
        if (!everything) {
            Set<String> seen = store.seen();
            batches = batches.stream().filter(b -> !seen.contains(RecallStore.seenKey(b))).toList();
        }
        if (batches.isEmpty()) {
            if (everything) {
                api.post(roomId, "Nothing flagged in " + source + ". No recall opened.");
            }
            return;
        }
        Recall recall = new Recall();
        recall.id = store.nextId();
        recall.openedAt = Instant.now();
        recall.source = source;
        recall.flaggedCount = batches.size();
        recall.alertsFile = store.writeAlerts(recall.id, source, batches);
        recall.note("Opened from " + source + ": " + batches.size() + " flagged batches.");
        store.markSeen(batches);
        save(recall);
        String hq = hqRoomId != null ? hqRoomId : roomId;
        api.post(hq, "🔔 **" + recall.id + " opened.** " + source + " lists **" + batches.size()
                + " flagged batches**.\n\n@" + props.agents().matcher() + " check them against every store's stock in `"
                + recall.alertsFile + "` and tell me `@" + props.agent().name() + " matched " + recall.id + "` when the impact table is ready.");
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    void poll() {
        int minutes = props.feeds().pollMinutes();
        if (minutes <= 0 || hqRoomId == null || Duration.between(lastPoll, Instant.now()).toMinutes() < minutes) {
            return;
        }
        lastPoll = Instant.now();
        try {
            scan(hqRoomId, "latest", false);
        } catch (RuntimeException e) {
            log.warn("Feed poll failed: {}", e.getMessage());
        }
    }

    private Optional<YearMonth> latestCdscoMonth() {
        YearMonth m = YearMonth.now();
        for (int i = 0; i < 4; i++, m = m.minusMonths(1)) {
            if (!cdsco.fetchMonth(m).isEmpty()) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    // ---- 2. the matcher reports what is on our shelves ---------------------------------------

    void matched(RoomMessage m, String id) {
        if (!fromAgent(m, props.agents().matcher()) || !m.roomId().equals(hqRoomId)) {
            api.post(m.roomId(), "Only @" + props.agents().matcher() + " reports matches, in the HQ room. Ignored.");
            return;
        }
        Optional<Recall> found = store.find(id);
        Optional<RecallStore.MatchFile> matches = store.readMatches(id);
        if (found.isEmpty() || matches.isEmpty()) {
            api.post(m.roomId(), "I can't find " + (found.isEmpty() ? "recall " + id : "data/matches/" + id + ".json") + ".");
            return;
        }
        Recall recall = found.get();
        if (recall.state != Recall.State.MATCHING) {
            api.post(m.roomId(), id + " is already past matching (" + RecallLog.label(recall.state) + "); its impact table can't change now.");
            return;
        }
        recall.stores.clear();
        for (RecallStore.StoreMatch s : matches.get().stores()) {
            if (s.lines() == null || s.lines().isEmpty()) {
                continue;
            }
            StoreOrder order = new StoreOrder();
            order.store = s.store();
            order.storeName = s.name();
            order.room = s.room();
            order.lines.addAll(s.lines());
            recall.stores.put(s.store(), order);
        }
        String hq = hqRoomId != null ? hqRoomId : m.roomId();
        if (recall.stores.isEmpty()) {
            recall.state = Recall.State.NO_IMPACT;
            recall.note(m.senderName() + " matched stock: no store holds any flagged batch.");
            save(recall);
            api.post(hq, "✅ **" + id + ": no stock affected.** None of the " + recall.flaggedCount + " flagged batches is on our shelves. Nothing to pull.");
            return;
        }
        recall.state = Recall.State.AWAITING_APPROVAL;
        int units = recall.stores.values().stream().mapToInt(StoreOrder::units).sum();
        long batches = recall.stores.values().stream().flatMap(s -> s.lines.stream()).map(l -> l.batch()).distinct().count();
        recall.note(m.senderName() + " matched stock: " + batches + " batches, " + recall.stores.size() + " stores, " + units + " units.");
        save(recall);
        StringBuilder msg = new StringBuilder("🧯 **" + id + " affects " + recall.stores.size() + " stores: " + batches
                + " batches, " + units + " units.** No order leaves this room until the QA Head approves.\n\n"
                + "| Store | Product | Batch | Qty | Shelf |\n|---|---|---|---|---|\n");
        for (StoreOrder s : recall.stores.values()) {
            for (StoreOrder.Line l : s.lines) {
                msg.append("| ").append(s.storeName).append(" | ").append(l.product()).append(" | ").append(l.batch())
                        .append(" | ").append(l.qty()).append(' ').append(nz(l.unit())).append(" | ").append(nz(l.shelf())).append(" |\n");
            }
        }
        msg.append("\nQA Head: reply `@").append(props.agent().name()).append(" approve ").append(id).append("` to send the stop-sale orders.");
        api.post(hq, msg.toString());
    }

    // ---- 3. the QA Head approves, and only the QA Head ----------------------------------------

    void approve(RoomMessage m, String id) {
        Optional<Recall> found = store.find(id);
        if (found.isEmpty()) {
            api.post(m.roomId(), "There is no recall " + id + ".");
            return;
        }
        Recall recall = found.get();
        ApprovalGate.Decision decision = gate.check(m, hqRoomId, recall);
        if (!decision.allowed()) {
            recall.note("Refused an approval from " + m.senderName() + " (`" + m.sender() + "`): " + decision.reason() + ".");
            save(recall);
            api.post(m.roomId(), "⛔ **Not accepted.** " + m.senderName() + ", I can't release " + id + ": " + decision.reason()
                    + ". The attempt is in the recall log.");
            return;
        }
        recall.state = Recall.State.IN_STORES;
        recall.approvedBy = m.senderName();
        recall.approvedById = m.sender();
        recall.approvedAt = Instant.now();
        recall.note("Approved by " + m.senderName() + ".");
        Map<String, String> rooms = myRooms();
        int sent = 0;
        for (StoreOrder s : recall.stores.values()) {
            Optional<String> roomId = lookup(rooms, s.room);
            if (roomId.isEmpty()) {
                recall.note("No single room named \"" + s.room + "\" for " + s.storeName + "; order not sent.");
                continue;
            }
            api.post(roomId.get(), order(recall, s));
            s.roomId = roomId.get();
            s.status = StoreOrder.Status.ORDERED;
            s.orderedAt = Instant.now();
            sent++;
            recall.note("Stop-sale order sent to " + s.storeName + ".");
        }
        save(recall);
        api.post(m.roomId(), "✅ **" + id + " approved by " + m.senderName() + ".** Stop-sale orders sent to " + sent + " of "
                + recall.stores.size() + " stores. I'll post here as each store confirms its shelves are clear.");
    }

    private String order(Recall recall, StoreOrder s) {
        StringBuilder msg = new StringBuilder("🛑 **STOP-SALE ORDER " + recall.id + "**, approved by " + recall.approvedBy + " (QA Head).\n\n"
                + s.storeName + ": take these off the shelf now and keep them in quarantine:\n");
        for (StoreOrder.Line l : s.lines) {
            msg.append("• ").append(l.product()).append(", batch **").append(l.batch()).append("**, ").append(l.qty()).append(' ')
                    .append(nz(l.unit())).append(l.shelf() == null || l.shelf().isBlank() ? "" : ", shelf " + l.shelf()).append('\n');
        }
        msg.append("\n@").append(props.agents().storeRunner()).append(" please get a shelf photo from the store manager, check the batch numbers against this order and report back to me (store=")
                .append(s.store).append(").");
        return msg.toString();
    }

    // ---- 4. stores confirm --------------------------------------------------------------------

    void storeReport(RoomMessage m, String id, String storeId, boolean recheck, String details) {
        Optional<Recall> found = store.find(id);
        if (found.isEmpty() || !found.get().stores.containsKey(storeId)) {
            api.post(m.roomId(), "I don't have an order for store `" + storeId + "` in " + id + ".");
            return;
        }
        Recall recall = found.get();
        StoreOrder s = recall.stores.get(storeId);
        // Only the store runner, in the room the order went to, while the order is open.
        if (!fromAgent(m, props.agents().storeRunner()) || !m.roomId().equals(s.roomId)) {
            api.post(m.roomId(), "Only @" + props.agents().storeRunner() + " in " + s.storeName + "'s room can report on its order. Ignored.");
            return;
        }
        if (recall.state != Recall.State.IN_STORES
                || (s.status != StoreOrder.Status.ORDERED && s.status != StoreOrder.Status.RECHECK)) {
            api.post(m.roomId(), "There is no open order for " + s.storeName + " in " + id + ".");
            return;
        }
        s.lastReport = details;
        String hq = hqRoomId != null ? hqRoomId : m.roomId();
        if (recheck) {
            s.status = StoreOrder.Status.RECHECK;
            recall.note(s.storeName + " needs a recheck (" + m.senderName() + "): " + details);
            save(recall);
            api.post(hq, "⚠️ **" + id + ": " + s.storeName + " is rechecking.** " + details);
            return;
        }
        s.status = StoreOrder.Status.CONFIRMED;
        s.confirmedAt = Instant.now();
        recall.note(s.storeName + " confirmed its shelves are clear (" + m.senderName() + "): " + details);
        long done = recall.storesConfirmed();
        if (done == recall.stores.size()) {
            recall.state = Recall.State.CLOSED;
            recall.note("Closed: every affected store confirmed.");
        }
        save(recall);
        api.post(hq, "📸 **" + id + ": " + s.storeName + " confirmed** (" + done + "/" + recall.stores.size() + " stores). " + details);
        if (recall.state == Recall.State.CLOSED) {
            int units = recall.stores.values().stream().mapToInt(StoreOrder::units).sum();
            api.post(hq, "🏁 **" + id + " closed.** " + units + " units are off the shelves in " + recall.stores.size()
                    + " stores, each with photo confirmation. The full trail is in the `recall-log` document.\n\n@"
                    + props.agents().comms() + " the shelves are clear; please finalise the patient notices for the pharmacist to send.");
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    void status(String roomId, String id) {
        List<Recall> recalls = id == null ? store.all() : store.find(id.toUpperCase(Locale.ROOT)).map(List::of).orElse(List.of());
        if (recalls.isEmpty()) {
            api.post(roomId, id == null ? "No recalls yet." : "There is no recall " + id + ".");
            return;
        }
        StringBuilder msg = new StringBuilder("| Recall | Source | State | Stores confirmed |\n|---|---|---|---|\n");
        for (Recall r : recalls.subList(0, Math.min(5, recalls.size()))) {
            msg.append("| ").append(r.id).append(" | ").append(r.source).append(" | ").append(RecallLog.label(r.state))
                    .append(" | ").append(r.storesConfirmed()).append('/').append(r.stores.size()).append(" |\n");
        }
        api.post(roomId, msg.toString());
    }

    private String help() {
        String me = "@" + props.agent().name();
        return "I'm the recall sentinel. In this room you can say:\n"
                + "• `" + me + " scan latest` (or `scan Sep-2026`, `scan openfda 30`) to open a recall from a regulator's list\n"
                + "• `" + me + " status` to see open recalls\n"
                + "• `" + me + " whoami` to see the id Switch gives your account\n"
                + "• `" + me + " approve R-…` to release stop-sale orders (QA Head only)";
    }

    private void save(Recall recall) {
        store.save(recall);
        refreshLog();
    }

    /** Creates the recall-log document in HQ the first time, then rewrites it. Only its author can update it. */
    private synchronized void refreshLog() {
        if (hqRoomId == null) {
            return;
        }
        String content = RecallLog.render(store.all());
        try {
            if (logDocumentId == null) {
                logDocumentId = store.logDocumentId().orElse(null);
            }
            if (logDocumentId == null) {
                logDocumentId = api.op("create_room_document", Map.of(
                        "name", "recall-log",
                        "description", "Audit trail of every recall: what was flagged, who approved, which stores confirmed",
                        "instructions", "Kept by sentinel. Read it for the state of any recall; do not try to edit it.",
                        "content", content)).path("document_id").asString();
                store.saveLogDocumentId(logDocumentId);
            } else {
                api.op("update_room_document", Map.of("document_id", logDocumentId, "content", content));
            }
        } catch (RuntimeException e) {
            log.warn("Could not update the recall-log document: {}", e.getMessage());
        }
    }

    /** Name to id for every room sentinel is in. */
    private Map<String, String> myRooms() {
        Map<String, String> rooms = new java.util.LinkedHashMap<>();
        for (JsonNode r : api.op("list_rooms", Map.of())) {
            rooms.put(r.path("name").asString(), r.path("room_id").asString());
        }
        return rooms;
    }

    private Optional<String> findRoom(String name) {
        return lookup(myRooms(), name);
    }

    /**
     * A room by its exact name, or by its exact title after a bridge prefix ("store-test" is
     * "Telegram stores: store-test"). Never a partial match, and nothing when two rooms qualify:
     * a stop-sale order must not land in the wrong store.
     */
    static Optional<String> lookup(Map<String, String> rooms, String name) {
        if (name == null) {
            return Optional.empty();
        }
        if (rooms.containsKey(name)) {
            return Optional.of(rooms.get(name));
        }
        String n = name.trim().toLowerCase(Locale.ROOT);
        List<String> hits = rooms.entrySet().stream()
                .filter(e -> {
                    String key = e.getKey().toLowerCase(Locale.ROOT);
                    int colon = key.lastIndexOf(": ");
                    return key.equals(n) || (colon >= 0 && key.substring(colon + 2).equals(n));
                })
                .map(Map.Entry::getValue)
                .toList();
        return hits.size() == 1 ? Optional.of(hits.get(0)) : Optional.empty();
    }

    /** Agents' Switch ids start "@switch-agent-"; people's come from their messaging app, so a renamed person can't pass. */
    static boolean fromAgent(RoomMessage m, String agentName) {
        return m.onBehalfOf() == null
                && m.sender() != null && m.sender().startsWith("@switch-agent-")
                && agentName.equalsIgnoreCase(m.senderName());
    }

    private static Optional<YearMonth> parseMonth(String s) {
        try {
            String t = s.trim();
            return Optional.of(YearMonth.parse(t.substring(0, 1).toUpperCase() + t.substring(1).toLowerCase(), MONTH));
        } catch (DateTimeParseException | StringIndexOutOfBoundsException e) {
            return Optional.empty();
        }
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
