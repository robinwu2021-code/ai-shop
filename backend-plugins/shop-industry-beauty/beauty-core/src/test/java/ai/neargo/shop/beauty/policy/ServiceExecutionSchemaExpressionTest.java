package ai.neargo.shop.beauty.policy;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

/** MySQL metadata quoting must not mask changed JSON paths or weakened boolean grouping. */
class ServiceExecutionSchemaExpressionTest {
    @Test void mysqlEscapedMetadataPreservesExactLiteralAndGrouping() {
        String original = "JSON_EXTRACT(binding_json,'$.binding.executionNo') = 'E1'";
        String metadata = "(json_extract(`binding_json`,_utf8mb4\\'$.binding.executionNo\\') = _utf8mb4\\'E1\\')";
        assertThat(ServiceExecutionSchemaVerifier.expression(metadata))
                .isEqualTo(ServiceExecutionSchemaVerifier.expression(original));
        assertThat(ServiceExecutionSchemaVerifier.expression(metadata.replace("executionNo", "executionno")))
                .isNotEqualTo(ServiceExecutionSchemaVerifier.expression(original));
        assertThat(ServiceExecutionSchemaVerifier.expression("a AND (b OR c)"))
                .isNotEqualTo(ServiceExecutionSchemaVerifier.expression("(a AND b) OR c"));
        assertThat(ServiceExecutionSchemaVerifier.expression("hash REGEXP '^[0-9a-f]{64}$'"))
                .isEqualTo(ServiceExecutionSchemaVerifier.expression("regexp_like(`hash`,_utf8mb4\\'^[0-9a-f]{64}$\\')"));
    }
}
