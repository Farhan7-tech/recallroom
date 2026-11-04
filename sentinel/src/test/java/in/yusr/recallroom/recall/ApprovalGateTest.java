package in.yusr.recallroom.recall;

import in.yusr.recallroom.config.SentinelProperties;
import in.yusr.recallroom.switchapi.RoomMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalGateTest {

    private static final String QA_HEAD = "@switch-mattermost-2d58b1ae-user:localhost";
    private static final String HQ = "room-hq";

    private final ApprovalGate gate = new ApprovalGate(new SentinelProperties(
            null, null, null, List.of(QA_HEAD), null, null, null, null));

    @Test
    void qaHeadInHqReleasesARecallWaitingForApproval() {
        assertThat(gate.check(message(QA_HEAD, "QA Head", null, HQ), HQ, waiting()).allowed()).isTrue();
    }

    @Test
    void someoneRenamedToQaHeadIsRefused() {
        var decision = gate.check(message("@switch-telegram-5aca7adb-store_staff:localhost", "QA Head", null, HQ), HQ, waiting());

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("only the QA Head");
    }

    @Test
    void anAgentPostingOnTheQaHeadsBehalfIsRefused() {
        var decision = gate.check(message(QA_HEAD, "QA Head", "@switch-agent-comms:localhost", HQ), HQ, waiting());

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("on someone else's behalf");
    }

    @Test
    void approvalsFromAStoreRoomDoNotCount() {
        assertThat(gate.check(message(QA_HEAD, "QA Head", null, "room-store"), HQ, waiting()).allowed()).isFalse();
    }

    @Test
    void aRecallCannotBeApprovedTwice() {
        Recall recall = waiting();
        recall.state = Recall.State.IN_STORES;

        assertThat(gate.check(message(QA_HEAD, "QA Head", null, HQ), HQ, recall).reason()).contains("not waiting for approval");
    }

    @Test
    void withNoQaHeadConfiguredNothingIsApproved() {
        ApprovalGate open = new ApprovalGate(new SentinelProperties(null, null, null, null, null, null, null, null));

        assertThat(open.configured()).isFalse();
        assertThat(open.check(message(QA_HEAD, "QA Head", null, HQ), HQ, waiting()).allowed()).isFalse();
    }

    private static Recall waiting() {
        Recall recall = new Recall();
        recall.id = "R-20261104-1";
        recall.state = Recall.State.AWAITING_APPROVAL;
        return recall;
    }

    private static RoomMessage message(String sender, String name, String onBehalfOf, String room) {
        return new RoomMessage(room, "m1", null, sender, name, null, onBehalfOf, "owner",
                "@sentinel approve R-20261104-1", true, List.of());
    }
}
