package in.yusr.recallroom.recall;

import in.yusr.recallroom.config.SentinelProperties;
import in.yusr.recallroom.switchapi.RoomMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Decides whether an "approve" message may release stop-sale orders. This is code, not a prompt:
 * an LLM can be talked into anything, a string comparison cannot.
 *
 * <p>Switch gives every message the sender's participant id, which is bound to the account on the
 * messaging app and does not change when someone renames themselves. Only the configured QA Head
 * ids pass, only in the HQ room, and never when an agent posted the message on someone's behalf.
 */
@Component
public class ApprovalGate {

    private final List<String> qaHeads;

    public ApprovalGate(SentinelProperties props) {
        this.qaHeads = props.qaHeads();
    }

    public Decision check(RoomMessage message, String hqRoomId, Recall recall) {
        if (message.onBehalfOf() != null) {
            return Decision.refuse("it was posted by an agent on someone else's behalf");
        }
        if (!qaHeads.contains(message.sender())) {
            return Decision.refuse("only the QA Head can approve a recall, and this account is not the QA Head's");
        }
        if (!message.roomId().equals(hqRoomId)) {
            return Decision.refuse("approvals count only in the HQ room, where the impact table is");
        }
        if (recall.state != Recall.State.AWAITING_APPROVAL) {
            return Decision.refuse(recall.id + " is " + recall.state.name().toLowerCase().replace('_', ' ') + ", not waiting for approval");
        }
        return Decision.ALLOW;
    }

    /**
     * The same identity checks as an approval, for the QA Head's other decisions on a recall that
     * is already out in stores (such as accepting a probable line as a stock-record typo).
     */
    public Decision checkQaDecision(RoomMessage message, String hqRoomId) {
        if (message.onBehalfOf() != null) {
            return Decision.refuse("it was posted by an agent on someone else's behalf");
        }
        if (!qaHeads.contains(message.sender())) {
            return Decision.refuse("only the QA Head can decide this, and this account is not the QA Head's");
        }
        if (!message.roomId().equals(hqRoomId)) {
            return Decision.refuse("QA decisions count only in the HQ room");
        }
        return Decision.ALLOW;
    }

    public boolean configured() {
        return !qaHeads.isEmpty();
    }

    public boolean isQaHead(String sender) {
        return qaHeads.contains(sender);
    }

    public record Decision(boolean allowed, String reason) {
        static final Decision ALLOW = new Decision(true, null);

        static Decision refuse(String reason) {
            return new Decision(false, reason);
        }
    }
}
