package ai.neargo.shop.food.config;

import java.sql.DatabaseMetaData;
import java.sql.Types;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FoodLocalStockSchemaContractTest {
    @Test void rejectsReducedScaleFloatingPointAndNullableQuantities() {
        assertThat(FoodLocalStockSchemaContract.exactDecimal(Types.DECIMAL, 24, 6, DatabaseMetaData.columnNoNulls)).isTrue();
        assertThat(FoodLocalStockSchemaContract.exactDecimal(Types.DECIMAL, 24, 2, DatabaseMetaData.columnNoNulls)).isFalse();
        assertThat(FoodLocalStockSchemaContract.exactDecimal(Types.DOUBLE, 24, 6, DatabaseMetaData.columnNoNulls)).isFalse();
        assertThat(FoodLocalStockSchemaContract.exactDecimal(Types.DECIMAL, 24, 6, DatabaseMetaData.columnNullable)).isFalse();
        assertThat(FoodLocalStockSchemaContract.nonNull(DatabaseMetaData.columnNullableUnknown)).isFalse();
    }

    @Test void requiresTheCompleteBalanceCheckRatherThanAnUnrelatedCheck() {
        assertThat(FoodLocalStockSchemaContract.hasExpectedCheck("fnb_stock_balance",
                List.of("((`on_hand` >= 0) AND (`reserved_qty` >= 0) AND (`reserved_qty` <= `on_hand`))"))).isTrue();
        assertThat(FoodLocalStockSchemaContract.hasExpectedCheck("fnb_stock_balance",
                List.of("on_hand >= 0"))).isFalse();
        assertThat(FoodLocalStockSchemaContract.hasExpectedCheck("fnb_stock_balance", List.of())).isFalse();
    }
}
