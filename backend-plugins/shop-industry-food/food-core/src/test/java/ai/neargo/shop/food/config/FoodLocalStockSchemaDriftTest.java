package ai.neargo.shop.food.config;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FoodLocalStockSchemaDriftTest {
    @Test void schemaGateRejectsDecimalTruncationNullableIdempotencyAndDisabledCheck() throws Exception {
        assertThat(FoodSchemaValidator.missingLocalStockContract(source(null))).isEmpty();
        assertThat(FoodSchemaValidator.missingLocalStockContract(source("scale")))
                .contains("fnb_stock_balance.on_hand.decimal_24_6");
        assertThat(FoodSchemaValidator.missingLocalStockContract(source("nullable")))
                .contains("fnb_stock_command.operation_key.not_null");
        assertThat(FoodSchemaValidator.missingLocalStockContract(source("disabled")))
                .contains("fnb_stock_balance.check_constraint");
    }

    private static DataSource source(String drift) throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        var statement = mock(PreparedStatement.class);
        var checks = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("testdb");
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        when(metadata.getColumns(any(), any(), anyString(), anyString())).thenAnswer(call -> {
            String table = call.getArgument(2), column = call.getArgument(3);
            var row = mock(ResultSet.class);
            when(row.next()).thenReturn(true);
            when(row.getInt("NULLABLE")).thenReturn("nullable".equals(drift)
                    && "fnb_stock_command".equals(table) && "operation_key".equals(column)
                    ? DatabaseMetaData.columnNullable : DatabaseMetaData.columnNoNulls);
            when(row.getInt("DATA_TYPE")).thenReturn(Types.DECIMAL);
            when(row.getInt("COLUMN_SIZE")).thenReturn(24);
            when(row.getInt("DECIMAL_DIGITS")).thenReturn("scale".equals(drift)
                    && "fnb_stock_balance".equals(table) && "on_hand".equals(column) ? 2 : 6);
            return row;
        });
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(checks);
        var entries = new ArrayList<>(FoodLocalStockSchemaContract.CHECKS.entrySet());
        final int[] index = {-1};
        when(checks.next()).thenAnswer(call -> ++index[0] < entries.size());
        when(checks.getString(1)).thenAnswer(call -> entries.get(index[0]).getKey());
        when(checks.getString(2)).thenAnswer(call -> entries.get(index[0]).getValue());
        when(checks.getString(3)).thenAnswer(call -> "disabled".equals(drift)
                && "fnb_stock_balance".equals(entries.get(index[0]).getKey()) ? "NO" : "YES");
        return source;
    }
}
