package in.yusr.recallroom.recall;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RoomLookupTest {

    private final Map<String, String> rooms = new LinkedHashMap<>(Map.of(
            "recall-hq", "r1",
            "Telegram stores: Andheri West store", "r2"));

    @Test
    void findsRoomsByExactNameOrByThePartAfterTheBridgePrefix() {
        assertThat(RecallService.lookup(rooms, "recall-hq")).contains("r1");
        assertThat(RecallService.lookup(rooms, "andheri west")).contains("r2");
        assertThat(RecallService.lookup(rooms, "pune")).isEmpty();
        assertThat(RecallService.lookup(rooms, null)).isEmpty();
    }
}
