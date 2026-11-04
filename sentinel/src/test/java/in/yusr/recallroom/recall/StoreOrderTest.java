package in.yusr.recallroom.recall;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StoreOrderTest {

    private final StoreOrder pune = order();

    @Test
    void findsTheBatchAStoreRunnerReports() {
        assertThat(pune.batchesIn("CHPLT1346 photo checked, 30 strips quarantined")).containsExactly("CHPLT1346");
        assertThat(pune.batchesIn("APRCT-2501 and UT260046 both checked")).containsExactly("APRCT-2501", "UT260046");
    }

    @Test
    void aZeroAndALetterOAreDifferentBatches() {
        assertThat(pune.batchesIn("APRCT-25O1 photo checked")).containsExactly("APRCT-25O1");
        assertThat(pune.batchesIn("APRCT-2501 photo checked")).containsExactly("APRCT-2501");
    }

    @Test
    void aStoreIsClearOnlyWhenEveryBatchIsChecked() {
        pune.confirmedBatches.addAll(pune.batchesIn("CHPLT1346 APRCT-2501 UT260046"));
        assertThat(pune.allBatchesConfirmed()).isFalse();

        pune.confirmedBatches.addAll(pune.batchesIn("APRCT-25O1"));
        assertThat(pune.allBatchesConfirmed()).isTrue();
    }

    private static StoreOrder order() {
        StoreOrder s = new StoreOrder();
        s.store = "pune";
        s.lines.add(new StoreOrder.Line("Telmisartan Tablets IP 40 mg", "CHPLT1346", 30, "strips", "T2", "exact", "", ""));
        s.lines.add(new StoreOrder.Line("Paracetamol IP 500 mg Tablets", "APRCT-2501", 15, "strips", "A1", "exact", "", ""));
        s.lines.add(new StoreOrder.Line("Paracetamol IP 500 mg Tablets", "APRCT-25O1", 5, "strips", "A1", "probable", "", ""));
        s.lines.add(new StoreOrder.Line("Aceclofenac & Paracetamol Tablets", "UT260046", 10, "strips", "A2", "exact", "", ""));
        return s;
    }
}
