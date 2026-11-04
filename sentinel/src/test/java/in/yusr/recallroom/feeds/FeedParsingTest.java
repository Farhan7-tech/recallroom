package in.yusr.recallroom.feeds;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FeedParsingTest {

    @Test
    void readsLotNumbersOutOfFdaCodeInfo() {
        assertThat(OpenFdaClient.lots("Lot # 7717825A, Expiry: 11/30/2028.")).containsExactly("7717825A");
        assertThat(OpenFdaClient.lots("Lot, expiry: [100 Tablets] Lots 8634085, 8635085, 8636085, exp 07/2027"))
                .containsExactly("8634085", "8635085", "8636085");
        assertThat(OpenFdaClient.lots("Lot 1028135, 1029555; Exp. Date 07/31/2027")).containsExactly("1028135", "1029555");
    }

    @Test
    void ignoresCodeInfoWithoutLots() {
        assertThat(OpenFdaClient.lots("NDC 17312-430-15")).isEmpty();
        assertThat(OpenFdaClient.lots(null)).isEmpty();
    }

    @Test
    void batchKeysIgnoreSpacingAndCaseAndFoldOcrLookalikes() {
        assertThat(BatchKeys.strict(" aprct-2501\n")).isEqualTo("APRCT2501");
        assertThat(BatchKeys.loose("APRCT-25O1")).isEqualTo(BatchKeys.loose("APRCT-2501"));
        assertThat(BatchKeys.strict("APRCT-25O1")).isNotEqualTo(BatchKeys.strict("APRCT-2501"));
    }
}
