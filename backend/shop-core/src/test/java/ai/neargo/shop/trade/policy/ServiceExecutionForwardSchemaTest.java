package ai.neargo.shop.trade.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Forward ACK/completion checks must remain inspectable without weakening their revision arithmetic. */
class ServiceExecutionForwardSchemaTest {
    private String expression(String value) throws Exception {
        Method parser = ServiceExecutionSchemaVerifier.class.getDeclaredMethod("expression", String.class);
        parser.setAccessible(true);
        return (String) parser.invoke(null, value);
    }

    @Test
    void nativeArithmeticAliasesDoNotEraseOriginalRevisionGuard() throws Exception {
        assertThat(expression("JSON_EXTRACT(proof_json,'$.resultRevision')=expected_revision+2"))
                .isEqualTo(expression("(json_extract(`proof_json`,'$.resultRevision')) = (`expected_revision` + 2)"))
                .isNotEqualTo(expression("JSON_EXTRACT(proof_json,'$.resultRevision')=expected_revision+1"));
        assertThat(expression("OCTET_LENGTH(proof_json)<=2097152"))
                .isEqualTo(expression("length(proof_json)<=2097152"))
                .isNotEqualTo(expression("length(proof_json)<=1048576"));
    }

    @Test
    void everyForwardPhysicalCheckUsesTheOwnersAcceptedGrammar() throws Exception {
        for (String file : new String[] {"service-execution-start-schema.json", "service-execution-completion-schema.json"}) {
            try (var input = getClass().getClassLoader().getResourceAsStream("META-INF/shop/" + file)) {
                assertThat(input).isNotNull();
                var descriptor = JsonMapper.builder().build().readTree(input);
                for (var table : descriptor.path("tables")) {
                    for (var check : table.path("checks").properties()) {
                        assertThat(expression(check.getValue().asString())).isNotBlank();
                    }
                }
            }
        }
    }
}
