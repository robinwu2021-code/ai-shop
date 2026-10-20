package ai.neargo.shop.beauty.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.Test;

class BeautyCheckExpressionTest {
    private String canonical(String expression) {
        var type = assertDoesNotThrow(() -> Class.forName("ai.neargo.shop.beauty.config.BeautyCheckExpression"));
        try {
            var method = type.getDeclaredMethod("canonical", String.class);
            method.setAccessible(true);
            return (String) method.invoke(null, expression);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new AssertionError(ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void ignoresOnlyRedundantGrouping() {
        assertEquals(canonical("done_qty + voided_qty <= qty"), canonical("((`done_qty` + `voided_qty`) <= `qty`)"));
    }

    @Test
    void preservesArithmeticAndComparisonPrecedence() {
        assertNotEquals(canonical("done_qty + voided_qty <= qty"), canonical("done_qty + (voided_qty <= qty)"));
    }

    @Test
    void preservesBooleanOperatorPrecedence() {
        assertNotEquals(canonical("a IS NULL OR b IS NOT NULL AND c >= d"),
                canonical("(a IS NULL OR b IS NOT NULL) AND c >= d"));
    }

    @Test
    void recognizesInListWithoutDiscardingValues() {
        assertEquals(canonical("deleted IN (0, 1)"), canonical("(`deleted` in (0,1))"));
        assertNotEquals(canonical("deleted IN (0, 1)"), canonical("deleted IN (0, 2)"));
    }

    @Test
    void rejectsUnknownOrIncompleteExpressions() {
        assertThrows(IllegalStateException.class, () -> canonical("a > 0; SELECT 1"));
        assertThrows(IllegalStateException.class, () -> canonical("a >="));
    }
}
