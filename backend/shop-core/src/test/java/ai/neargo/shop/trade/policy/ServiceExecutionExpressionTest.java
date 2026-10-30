package ai.neargo.shop.trade.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/** Source-owned constraint grammar accepts only the native no-flags two-argument REGEXP spelling alias. */
class ServiceExecutionExpressionTest {
    private String expression(String sql) throws Exception {
        Method method = ServiceExecutionSchemaVerifier.class.getDeclaredMethod("expression", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, sql);
    }

    @Test
    void actualOwnerHashCheckAcceptsOnlyEquivalentTwoArgumentFunctionSyntax() throws Exception {
        String original = expression("binding_hash REGEXP '^[0-9a-f]{64}$'");
        assertThat(expression("regexp_like((`binding_hash`),_utf8mb4'^[0-9a-f]{64}$')"))
                .isEqualTo(original);
        assertThat(expression("regexp_like(binding_hash,'^[0-9a-f]{64}$','i')")).isNotEqualTo(original);
        assertThat(expression("regexp_like(binding_hash,'^[0-9a-f]{64}$','c')")).isNotEqualTo(original);
        assertThat(expression("regexp_like(binding_hash,'^[0-9A-F]{64}$')")).isNotEqualTo(original);
        assertThat(expression("regexp_like(other_hash,'^[0-9a-f]{64}$')")).isNotEqualTo(original);
    }

    @Test
    void aliasCannotChangeBooleanGroupingOrLiteralPayloadBytes() throws Exception {
        String original = expression("binding_hash IS NULL OR binding_hash REGEXP '^[0-9a-f]{64}$'");
        assertThat(expression("(binding_hash IS NULL) OR regexp_like(binding_hash,'^[0-9a-f]{64}$')"))
                .isEqualTo(original);
        assertThat(expression("binding_hash IS NULL AND regexp_like(binding_hash,'^[0-9a-f]{64}$')"))
                .isNotEqualTo(original);
        assertThat(expression("binding_hash IS NULL OR regexp_like(binding_hash,'^[0-9a-f]{63}$')"))
                .isNotEqualTo(original);
    }
}
