package ai.neargo.shop.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

import java.sql.*;

import javax.sql.DataSource;

class FlywayMariaDbDialectDataSourceTest {
    @Test
    void mariaWireAdaptsDropCheckButLeavesMetadataAndOtherSqlUntouched() throws Exception {
        var source = mock(DataSource.class);
        var original = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        var statement = mock(Statement.class);
        when(source.getConnection()).thenReturn(original);
        when(original.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        when(metadata.getDatabaseProductVersion()).thenReturn("11.4.8-MariaDB");
        when(original.createStatement()).thenReturn(statement);
        var connection = new FlywayMariaDbDialectDataSource(source).getConnection();
        connection
                .createStatement()
                .execute(
                        "-- unchanged resource comment\n"
                                + "ALTER TABLE ent_usage DROP CHECK ck_usage_protocol");
        verify(statement)
                .execute(
                        "-- unchanged resource comment\n"
                                + "ALTER TABLE ent_usage DROP CONSTRAINT ck_usage_protocol");
        connection.createStatement().execute("SELECT 'DROP CHECK' AS unchanged");
        verify(statement).execute("SELECT 'DROP CHECK' AS unchanged");
        connection.prepareStatement("ALTER TABLE ent_usage DROP CHECK ck_usage_protocol");
        verify(original)
                .prepareStatement("ALTER TABLE ent_usage DROP CONSTRAINT ck_usage_protocol");
        connection.close();
        verify(original).close();
    }

    @Test
    void mysqlConnectionAndOriginalSqlAreNotWrappedOrRewritten() throws Exception {
        var source = mock(DataSource.class);
        var original = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        when(source.getConnection()).thenReturn(original);
        when(original.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        when(metadata.getDatabaseProductVersion()).thenReturn("9.7.2");
        assertThat(new FlywayMariaDbDialectDataSource(source).getConnection()).isSameAs(original);
    }

    private static String originalV550Set() throws Exception {
        return java.nio.file.Files.readAllLines(
                        java.nio.file.Path.of(
                                "src/main/resources/db/migration/V550__stored_value_legacy_activity_release.sql"))
                .stream()
                .filter(line -> line.startsWith("SET @sv_release_sql = CONCAT("))
                .reduce(
                        (a, b) -> {
                            throw new AssertionError("One original V550 dynamic DDL expected");
                        })
                .orElseThrow();
    }

    @Test
    void mariaAdaptsOnlyTheOriginalV550DynamicDropAndPreservesTheRemainingBytes() throws Exception {
        var source = mock(DataSource.class);
        var original = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        var statement = mock(Statement.class);
        when(source.getConnection()).thenReturn(original);
        when(original.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MariaDB");
        when(metadata.getDatabaseProductVersion()).thenReturn("11.4.8-MariaDB");
        when(original.createStatement()).thenReturn(statement);
        String actual = originalV550Set();
        String expected =
                actual.replace(
                        "ALTER TABLE sv_release DROP CHECK `",
                        "ALTER TABLE sv_release DROP CONSTRAINT `");
        var connection = new FlywayMariaDbDialectDataSource(source).getConnection();
        connection.createStatement().execute(actual);
        verify(statement).execute(expected);
        connection.createStatement().addBatch(actual);
        verify(statement).addBatch(expected);
        connection.prepareStatement(actual);
        verify(original).prepareStatement(expected);
        String withoutTerminator = actual.substring(0, actual.length() - 1);
        connection.prepareCall(withoutTerminator);
        verify(original).prepareCall(expected.substring(0, expected.length() - 1));
        connection.createStatement().executeLargeUpdate(" \n" + actual + "\n ");
        verify(statement).executeLargeUpdate(" \n" + expected + "\n ");
        // The ordinary datasource is untouched even when its real engine is MariaDB.
        source.getConnection().createStatement().execute(actual);
        verify(statement).execute(actual);
    }

    @Test
    void nearbyDynamicStatementsAndSelectLiteralsAreNeverRewritten() throws Exception {
        var source = mock(DataSource.class);
        var original = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        var statement = mock(Statement.class);
        when(source.getConnection()).thenReturn(original);
        when(original.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MariaDB");
        when(metadata.getDatabaseProductVersion()).thenReturn("11.4.8-MariaDB");
        when(original.createStatement()).thenReturn(statement);
        var connection = new FlywayMariaDbDialectDataSource(source).getConnection();
        String sql = originalV550Set();
        for (String unchanged :
                java.util.List.of(
                        sql.replace("@sv_release_sql", "@other_sql"),
                        sql.replace("@sv_release_check", "@other_check"),
                        sql.replace("ALTER TABLE sv_release ", "ALTER TABLE other_table "),
                        "SELECT CONCAT('ALTER TABLE sv_release DROP CHECK"
                                + " `',REPLACE(@sv_release_check,'`','``'),'`');",
                        "SELECT '" + sql.replace("'", "''") + "' AS original_literal")) {
            connection.createStatement().execute(unchanged);
            verify(statement).execute(unchanged);
        }
    }

    @Test
    void mysqlPreservesTheActualV550DynamicDdl() throws Exception {
        var source = mock(DataSource.class);
        var original = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        var statement = mock(Statement.class);
        when(source.getConnection()).thenReturn(original);
        when(original.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        when(metadata.getDatabaseProductVersion()).thenReturn("9.7.2");
        when(original.createStatement()).thenReturn(statement);
        var connection = new FlywayMariaDbDialectDataSource(source).getConnection();
        assertThat(connection).isSameAs(original);
        String sql = originalV550Set();
        connection.createStatement().execute(sql);
        verify(statement).execute(sql);
    }
}
