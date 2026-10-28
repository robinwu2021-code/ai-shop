package ai.neargo.shop.inventory.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/** Native servers may spell the same two-argument regex check differently; literals and flags remain exact. */
class ExecutionRetentionExpressionTest {
    private String expression(String sql) throws Exception {
        Method method = ExecutionStockRetentionSchemaVerifier.class.getDeclaredMethod("expression", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, sql);
    }

    @Test
    void onlyTheTwoArgumentRegexpFunctionAndOperatorAreEquivalent() throws Exception {
        String operator = expression("reference_hash REGEXP '^[a-f0-9]{64}$'");
        assertThat(expression("REGEXP_LIKE((`reference_hash`),_utf8mb4'^[a-f0-9]{64}$')"))
                .isEqualTo(operator);
        assertThat(expression("REGEXP_LIKE(reference_hash,'^[a-f0-9]{64}$','i')")).isNotEqualTo(operator);
        assertThat(expression("REGEXP_LIKE(reference_hash,'^[a-f0-9]{64}$','c')")).isNotEqualTo(operator);
        assertThat(expression("REGEXP_LIKE(reference_hash,'^[A-F0-9]{64}$')")).isNotEqualTo(operator);
        assertThat(expression("REGEXP_LIKE(other_hash,'^[a-f0-9]{64}$')")).isNotEqualTo(operator);
    }

    @Test
    void regexNormalizationDoesNotFlattenBooleanPrecedenceOrChangeLiteralBytes() throws Exception {
        assertThat(expression("state='RETAINED' AND reference_hash REGEXP '^[a-f0-9]{64}$'"))
                .isEqualTo(expression("(state='RETAINED') AND REGEXP_LIKE(reference_hash,'^[a-f0-9]{64}$')"));
        assertThat(expression("state='RETAINED' OR REGEXP_LIKE(reference_hash,'^[a-f0-9]{64}$')"))
                .isNotEqualTo(expression("state='RETAINED' AND reference_hash REGEXP '^[a-f0-9]{64}$'"));
    }
}
