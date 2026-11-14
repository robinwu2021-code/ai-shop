package ai.neargo.shop.product.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/** Source-owned constraint grammar accepts only the native no-flags two-argument REGEXP spelling alias. */
class ExecutionStockRetentionExpressionTest {
    private String expression(String sql) throws Exception {
        Method method = ExecutionStockRetentionSchemaVerifier.class.getDeclaredMethod("expression", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, sql);
    }

    @Test
    void actualOwnerHashCheckAcceptsOnlyEquivalentTwoArgumentFunctionSyntax() throws Exception {
        String original = expression("request_hash REGEXP '^[0-9a-f]{64}$'");
        assertThat(expression("regexp_like((`request_hash`),_utf8mb4'^[0-9a-f]{64}$')"))
                .isEqualTo(original);
        assertThat(expression("regexp_like(request_hash,'^[0-9a-f]{64}$','i')")).isNotEqualTo(original);
        assertThat(expression("regexp_like(request_hash,'^[0-9a-f]{64}$','c')")).isNotEqualTo(original);
        assertThat(expression("regexp_like(request_hash,'^[0-9A-F]{64}$')")).isNotEqualTo(original);
        assertThat(expression("regexp_like(other_hash,'^[0-9a-f]{64}$')")).isNotEqualTo(original);
    }

    @Test
    void aliasCannotChangeBooleanGroupingOrLiteralPayloadBytes() throws Exception {
        String original = expression("request_hash IS NULL OR request_hash REGEXP '^[0-9a-f]{64}$'");
        assertThat(expression("(request_hash IS NULL) OR regexp_like(request_hash,'^[0-9a-f]{64}$')"))
                .isEqualTo(original);
        assertThat(expression("request_hash IS NULL AND regexp_like(request_hash,'^[0-9a-f]{64}$')"))
                .isNotEqualTo(original);
        assertThat(expression("request_hash IS NULL OR regexp_like(request_hash,'^[0-9a-f]{63}$')"))
                .isNotEqualTo(original);
    }
}
