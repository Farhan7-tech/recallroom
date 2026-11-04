package in.yusr.recallroom.recall;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Renders the {@code recall-log} room document: the audit trail a drug inspector would ask for. */
final class RecallLog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.of("Asia/Kolkata"));

    private RecallLog() {
    }

    static String render(List<Recall> recalls) {
        StringBuilder md = new StringBuilder("# Recall log\n\n")
                .append("Kept by sentinel. One section per recall, newest first. Times are IST.\n");
        if (recalls.isEmpty()) {
            md.append("\nNo recalls yet.\n");
        }
        for (Recall r : recalls) {
            md.append("\n## ").append(r.id).append(" · ").append(label(r.state)).append("\n\n")
                    .append("- **Source:** ").append(r.source).append(" (").append(r.flaggedCount).append(" flagged batches)\n")
                    .append("- **Opened:** ").append(TIME.format(r.openedAt)).append('\n');
            if (r.approvedAt != null) {
                md.append("- **Approved by:** ").append(r.approvedBy).append(" at ").append(TIME.format(r.approvedAt))
                        .append(" (`").append(r.approvedById).append("`)\n");
            }
            if (!r.stores.isEmpty()) {
                md.append("\n| Store | Batches | Units | Status | Confirmed |\n|---|---|---|---|---|\n");
                for (StoreOrder s : r.stores.values()) {
                    md.append("| ").append(s.storeName).append(" | ")
                            .append(String.join(", ", s.lines.stream().map(StoreOrder.Line::batch).toList())).append(" | ")
                            .append(s.units()).append(" | ").append(s.status.name().toLowerCase().replace('_', ' ')).append(" | ")
                            .append(s.confirmedAt == null ? "" : TIME.format(s.confirmedAt)).append(" |\n");
                }
            }
            md.append("\n**Timeline**\n\n");
            for (Recall.Event e : r.timeline) {
                md.append("- ").append(TIME.format(e.at())).append(" · ").append(e.text()).append('\n');
            }
        }
        return md.toString();
    }

    static String label(Recall.State state) {
        return switch (state) {
            case MATCHING -> "matching stock";
            case AWAITING_APPROVAL -> "waiting for QA Head";
            case NO_IMPACT -> "no stock affected";
            case IN_STORES -> "orders out in stores";
            case CLOSED -> "closed";
        };
    }
}
