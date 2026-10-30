package ai.neargo.shop.inventory.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class InventoryFlywayDataSourceTest {
    @Test
    void onlySqlCollationTokensChangeWhileQuotedTextAndCommentsRemainIntact() {
        String sql = "-- COLLATE utf8mb4_uca1400_ai_ci\n"
                + "CREATE TABLE `COLLATE utf8mb4_uca1400_ai_ci` ("
                + "s VARCHAR(32) COLLATE utf8mb4_uca1400_ai_ci "
                + "COMMENT 'it''s COLLATE utf8mb4_uca1400_ai_ci', "
                + "t VARCHAR(32) COMMENT \"COLLATE utf8mb4_uca1400_ai_ci\") "
                + "/* COLLATE=utf8mb4_uca1400_ai_ci */ COLLATE=utf8mb4_uca1400_ai_ci;";
        assertThat(InventoryFlywayDataSource.rewrite(sql)).isEqualTo(sql
                .replace("s VARCHAR(32) COLLATE utf8mb4_uca1400_ai_ci", "s VARCHAR(32) COLLATE utf8mb4_unicode_520_ci")
                .replace("*/ COLLATE=utf8mb4_uca1400_ai_ci", "*/ COLLATE=utf8mb4_unicode_520_ci"));
        assertThat(InventoryFlywayDataSource.rewrite("SELECT 'escaped \\' COLLATE utf8mb4_uca1400_ai_ci'"))
                .isEqualTo("SELECT 'escaped \\' COLLATE utf8mb4_uca1400_ai_ci'");
        assertThat(InventoryFlywayDataSource.rewrite("ALTER TABLE x COLLATE utf8mb4_uca1400_ai_ci_suffix"))
                .endsWith("utf8mb4_uca1400_ai_ci_suffix");
    }

    @Test
    void mysqlMigrationStatementsPreparedCallsAndBatchesUseTheSameTranslation() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection raw = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        Statement statement = mock(Statement.class);
        when(source.getConnection()).thenReturn(raw);
        when(raw.getMetaData()).thenReturn(meta);
        when(meta.getDatabaseProductName()).thenReturn("MySQL");
        when(meta.getDatabaseProductVersion()).thenReturn("9.7.2");
        when(raw.createStatement()).thenReturn(statement);
        Connection migrated = new InventoryFlywayDataSource(source).getConnection();
        String original = "ALTER TABLE x COLLATE=utf8mb4_uca1400_ai_ci";
        String expected = "ALTER TABLE x COLLATE=utf8mb4_unicode_520_ci";
        migrated.createStatement().execute(original);
        migrated.createStatement().addBatch(original);
        migrated.prepareStatement(original);
        migrated.prepareCall(original);
        verify(statement).execute(expected);
        verify(statement).addBatch(expected);
        verify(raw).prepareStatement(expected);
        verify(raw).prepareCall(expected);
        // Normal Inventory connections remain the original datasource, without adaptation.
        source.getConnection().createStatement().execute(original);
        verify(statement).execute(original);
        migrated.close();
        verify(raw).close();
    }

    @Test
    void mariaDbAndNonMysqlConnectionsAreUnchanged() throws Exception {
        for (String engine : new String[] {"MariaDB", "H2", "MySQL"}) {
            DataSource source = mock(DataSource.class);
            Connection raw = mock(Connection.class);
            DatabaseMetaData meta = mock(DatabaseMetaData.class);
            when(source.getConnection()).thenReturn(raw);
            when(raw.getMetaData()).thenReturn(meta);
            when(meta.getDatabaseProductName()).thenReturn(engine);
            when(meta.getDatabaseProductVersion()).thenReturn("MySQL".equals(engine) ? "11.4.8-MariaDB" : "11.4.8");
            assertThat(new InventoryFlywayDataSource(source).getConnection()).isSameAs(raw);
        }
    }
}
