package ai.neargo.shop.beauty.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Statement;

/** Only exact frozen V10 DDL may change at the JDBC migration boundary. */
class BeautyMigrationDataSourceTest {
    String ddl() throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream(
                "db/industry/beauty/V10__beauty_service_execution_fence.sql")) {
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return sql.substring(0, sql.indexOf(';', sql.indexOf("CREATE TABLE")) + 1);
        }
    }
    @Test void exactCreateIsBooleanAndUnrelatedSqlAndLiteralsStayUntouched() throws Exception {
        String original = ddl();
        String fixed = BeautyMigrationDataSource.rewrite(original);
        assertThat(fixed).contains("CHECK ((COALESCE(").doesNotContain("CHECK (COALESCE(");
        assertThat(fixed).contains("=execution_no,0))=1)");
        assertThat(BeautyMigrationDataSource.rewrite(original.replace("bty_service_execution_fence", "other")))
                .isEqualTo(original.replace("bty_service_execution_fence", "other"));
        String literal = "INSERT INTO audit VALUES ('" + original + "')";
        assertThat(BeautyMigrationDataSource.rewrite(literal)).isEqualTo(literal);
        assertThat(BeautyMigrationDataSource.rewrite(fixed)).isEqualTo(fixed);
    }
    @Test void mysqlMigrationStatementUsesAdapterWithoutChangingOrdinaryDatasource() throws Exception {
        var source = mock(DataSource.class); var connection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class); var statement = mock(Statement.class);
        when(source.getConnection()).thenReturn(connection); when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        when(connection.createStatement()).thenReturn(statement);
        try (var migration = new BeautyMigrationDataSource(source).getConnection()) {
            migration.createStatement().execute(ddl());
        }
        verify(statement).execute(BeautyMigrationDataSource.rewrite(ddl()));
        assertThat(source.getConnection()).isSameAs(connection);
    }
}
