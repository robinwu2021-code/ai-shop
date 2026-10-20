package ai.neargo.shop.config;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
class FlywayMariaDbDialectDataSourceTest {
    @Test void mariaWireAdaptsDropCheckButLeavesMetadataAndOtherSqlUntouched() throws Exception {
        var source=mock(DataSource.class);var original=mock(Connection.class);var metadata=mock(DatabaseMetaData.class);var statement=mock(Statement.class);
        when(source.getConnection()).thenReturn(original);when(original.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");when(metadata.getDatabaseProductVersion()).thenReturn("11.4.8-MariaDB");when(original.createStatement()).thenReturn(statement);
        var connection=new FlywayMariaDbDialectDataSource(source).getConnection();
        connection.createStatement().execute("-- unchanged resource comment\nALTER TABLE ent_usage DROP CHECK ck_usage_protocol");
        verify(statement).execute("-- unchanged resource comment\nALTER TABLE ent_usage DROP CONSTRAINT ck_usage_protocol");
        connection.createStatement().execute("SELECT 'DROP CHECK' AS unchanged");verify(statement).execute("SELECT 'DROP CHECK' AS unchanged");
        connection.prepareStatement("ALTER TABLE ent_usage DROP CHECK ck_usage_protocol");
        verify(original).prepareStatement("ALTER TABLE ent_usage DROP CONSTRAINT ck_usage_protocol");
        connection.close();verify(original).close();
    }
    @Test void mysqlConnectionAndOriginalSqlAreNotWrappedOrRewritten() throws Exception {
        var source=mock(DataSource.class);var original=mock(Connection.class);var metadata=mock(DatabaseMetaData.class);
        when(source.getConnection()).thenReturn(original);when(original.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");when(metadata.getDatabaseProductVersion()).thenReturn("9.7.2");
        assertThat(new FlywayMariaDbDialectDataSource(source).getConnection()).isSameAs(original);
    }
}
