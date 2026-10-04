package panel;

import java.util.List;
import org.junit.jupiter.api.Test;
import panel.ui.trader.TTable;
import static org.junit.jupiter.api.Assertions.*;

class IncrementalTableTest {
    @Test void updatesOnlyChangedRowsAndPreservesTableAndUnchangedItems() throws Exception {
        FxSupport.fx(() -> {
            String[] first = {"ETHUSDT", "N/A"}, second = {"Order", "Pending"};
            var table = TTable.of(new String[] {"Name", "State"}, List.of(first, second), "Unavailable", 120);
            var columns = List.copyOf(table.getColumns());
            TTable.update(table, List.of(new String[] {"ETHUSDT", "N/A"}, new String[] {"Order", "Confirmed"}));
            assertSame(first, table.getItems().getFirst());
            assertEquals("Confirmed", table.getItems().get(1)[1]);
            assertEquals(columns, table.getColumns());
            TTable.update(table, List.<String[]>of(new String[] {"ETHUSDT", "N/A"}));
            assertEquals(1, table.getItems().size());
            assertSame(first, table.getItems().getFirst());
            TTable.update(table, List.of(first, second));
            assertEquals(2, table.getItems().size());
        });
    }
}
