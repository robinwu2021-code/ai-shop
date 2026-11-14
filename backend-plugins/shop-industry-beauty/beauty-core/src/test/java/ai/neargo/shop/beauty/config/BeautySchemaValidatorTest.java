package ai.neargo.shop.beauty.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;

/** Exercises the actual startup trigger comparison at its JDBC metadata boundary. */
class BeautySchemaValidatorTest {
    @Test
    void frozenAndHarmlessMysqlFormattingPassStartupTriggerInspection() throws Exception {
        String frozen = frozenBody();
        assertThatCode(() -> inspect(frozen)).doesNotThrowAnyException();
        String formatted =
                frozen.replace("BEGIN", "begin")
                        .replace("OLD.admission_kind", "old.`admission_kind`")
                        .replace("NEW.admission_kind", "new.`admission_kind`")
                        .replace(" THEN", " then")
                        .replace("\n", "\n\t  ");
        assertThatCode(() -> inspect(formatted)).doesNotThrowAnyException();
    }

    @Test
    void manualDomainLiteralCaseDriftFailsStartupTriggerInspection() throws Exception {
        String changed = frozenBody().replaceFirst("'MANUAL_OFFLINE'", "'manual_offline'");
        assertThatThrownBy(() -> inspect(changed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trigger differs");
    }

    @Test
    void messageLiteralWhitespaceDriftFailsStartupTriggerInspection() throws Exception {
        String changed =
                frozenBody().replace("Immutable beauty manual", "Immutable  beauty manual");
        assertThatThrownBy(() -> inspect(changed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trigger differs");
    }

    @Test
    void literalBackticksAreNotIdentifierFormatting() throws Exception {
        String changed =
                frozenBody().replace("Immutable beauty manual", "Immutable beauty `manual`");
        assertThatThrownBy(() -> inspect(changed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trigger differs");
    }

    @Test
    void baseManifestHas28TablesAndV10IsOneExtension() throws Exception {
        var method = BeautySchemaValidator.class.getDeclaredMethod("manifest");
        method.setAccessible(true);
        var base = (Map<?, ?>) method.invoke(null);
        assertThat(base).hasSize(28);
        assertThat(base.containsKey("bty_service_execution_fence")).isFalse();
        try (var input = getClass().getResourceAsStream("/META-INF/shop/beauty-service-execution-fence-schema.json")) {
            var contract = JsonMapper.builder().build().readTree(input);
            assertThat(contract.path("platformVersion").asInt()).isEqualTo(10);
            assertThat(contract.path("tables").size()).isOne();
            assertThat(contract.path("tables").get(0).path("name").asString())
                    .isEqualTo("bty_service_execution_fence");
        }
    }

    private static String frozenBody() throws Exception {
        try (var input =
                BeautySchemaValidator.class.getResourceAsStream(
                        "/db/industry/beauty/V9__beauty_manual_source_observation.sql")) {
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return sql.substring(sql.indexOf("\nBEGIN\n") + 1, sql.lastIndexOf("$$\nDELIMITER"));
        }
    }

    private static void inspect(String actual) throws Exception {
        var dataSource = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString(1)).thenReturn("trg_bty_source_observation_domain_guard");
        when(rows.getString(2)).thenReturn("UPDATE");
        when(rows.getString(3)).thenReturn("BEFORE");
        when(rows.getString(4)).thenReturn("bty_source_observation");
        when(rows.getString(5)).thenReturn(actual);
        var inspection =
                BeautySchemaValidator.class.getDeclaredMethod(
                        "validateManualTrigger", DataSource.class);
        inspection.setAccessible(true);
        try {
            inspection.invoke(null, dataSource);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            throw failure;
        }
    }
}
