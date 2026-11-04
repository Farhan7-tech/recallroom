package in.yusr.recallroom.recall;

import in.yusr.recallroom.switchapi.RoomMessage;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RoomLookupTest {

    private final Map<String, String> rooms = new LinkedHashMap<>(Map.of(
            "recall-hq", "r1",
            "Telegram stores: store-test", "r2",
            "store-pune", "r3"));

    @Test
    void findsRoomsByExactNameOrByTheExactTitleAfterTheBridgePrefix() {
        assertThat(RecallService.lookup(rooms, "recall-hq")).contains("r1");
        assertThat(RecallService.lookup(rooms, "store-test")).contains("r2");
        assertThat(RecallService.lookup(rooms, "Store-Pune")).contains("r3");
    }

    @Test
    void neverRoutesAnOrderOnAPartialName() {
        assertThat(RecallService.lookup(rooms, "store")).isEmpty();
        assertThat(RecallService.lookup(rooms, "pune")).isEmpty();
        assertThat(RecallService.lookup(rooms, null)).isEmpty();
    }

    @Test
    void refusesWhenTwoRoomsShareATitle() {
        Map<String, String> clash = Map.of("Telegram stores: andheri", "a", "Discord: andheri", "b");

        assertThat(RecallService.lookup(clash, "andheri")).isEmpty();
    }

    @Test
    void agentReportsMustComeFromThatAgentsOwnSwitchId() {
        assertThat(RecallService.looksLikeAgent(msg("@switch-agent-acdd6087:localhost", "store-runner", null), "store-runner")).isTrue();
        // a person who renamed themselves "store-runner" on Telegram
        assertThat(RecallService.looksLikeAgent(msg("@switch-telegram-5aca-mohd:localhost", "store-runner", null), "store-runner")).isFalse();
        // another agent, or any agent speaking for someone else
        assertThat(RecallService.looksLikeAgent(msg("@switch-agent-99:localhost", "comms", null), "store-runner")).isFalse();
        assertThat(RecallService.looksLikeAgent(msg("@switch-agent-acdd6087:localhost", "store-runner", "@x"), "store-runner")).isFalse();
    }

    private static RoomMessage msg(String sender, String name, String onBehalfOf) {
        return new RoomMessage("r2", "m", null, sender, name, null, onBehalfOf, null, "", true, List.of());
    }
}
