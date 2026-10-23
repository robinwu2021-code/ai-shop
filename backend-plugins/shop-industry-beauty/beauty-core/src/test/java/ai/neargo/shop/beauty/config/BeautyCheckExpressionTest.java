package ai.neargo.shop.beauty.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;

class BeautyCheckExpressionTest {
    private String canonical(String expression) {
        var type =
                assertDoesNotThrow(
                        () -> Class.forName("ai.neargo.shop.beauty.config.BeautyCheckExpression"));
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
        assertEquals(
                canonical("done_qty + voided_qty <= qty"),
                canonical("((`done_qty` + `voided_qty`) <= `qty`)"));
    }

    @Test
    void preservesArithmeticAndComparisonPrecedence() {
        assertNotEquals(
                canonical("done_qty + voided_qty <= qty"),
                canonical("done_qty + (voided_qty <= qty)"));
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
    void recognizesOnlyApprovedPayloadFunctions() {
        assertEquals(
                canonical("JSON_VALID(frozen_plan) AND OCTET_LENGTH(frozen_plan) <= 65536"),
                canonical(
                        "(json_valid(`frozen_plan`) and (octet_length(`frozen_plan`) <= 65536))"));
        assertEquals(canonical("OCTET_LENGTH(frozen_plan) <= 65536"),
                canonical("length(`frozen_plan`) <= 65536"));
        assertNotEquals(canonical("OCTET_LENGTH(frozen_plan) <= 65536"),
                canonical("OCTET_LENGTH(frozen_plan) <= 65535"));
        assertThrows(IllegalStateException.class, () -> canonical("sleep(1)"));
        assertThrows(IllegalStateException.class, () -> canonical("JSON_VALID(a,b)"));
    }

    @Test
    void rejectsUnknownOrIncompleteExpressions() {
        assertThrows(IllegalStateException.class, () -> canonical("a > 0; SELECT 1"));
        assertThrows(IllegalStateException.class, () -> canonical("a >="));
    }
    @Test
    void receptionStringChecksPreserveCaseAndRejectUnknownFunctions() {
        assertEquals(canonical("phase IN ('RECEIVED','SUCCEEDED')"),
                canonical("(`phase` in (_utf8mb4'RECEIVED',_utf8mb4'SUCCEEDED'))"));
        assertNotEquals(canonical("phase = 'USER'"), canonical("phase = 'user'"));
        assertEquals(canonical("phase = 'USER'"), canonical("phase = _utf8mb4\\'USER\\'"));
        assertEquals(canonical("phase = 'USER'"), canonical("phase = _latin1\\'USER\\'"));
        assertEquals(canonical("CHAR_LENGTH(code) = 43"), canonical("(char_length(`code`) = 43)"));
        assertThrows(IllegalStateException.class, () -> canonical("concat(code) = 'x'"));
        assertThrows(IllegalStateException.class, () -> canonical("phase = 'unterminated"));
    }
    @Test void quotedIdentifierNeverErasesLiteralContent() {
        assertEquals(canonical("phase = 'RECEIVED'"), canonical("`phase` = 'RECEIVED'"));
        assertNotEquals(canonical("phase = 'RECEIVED'"), canonical("phase = 'RECE`IVED'"));
        assertThrows(
                IllegalStateException.class, () -> canonical("phase = _utf8mb4\\'RECE`IVED\\'"));
    }

    @Test
    void sourceArrayChecksRetainTypeLengthAndRejectOtherFunctions() {
        assertEquals(
                "and(=(json_type(verified_lines),literal('ARRAY')),<=(json_length(verified_lines),20))",
                assertDoesNotThrow(
                        () ->
                                canonical(
                                        "JSON_TYPE(verified_lines) = 'ARRAY' AND"
                                            + " JSON_LENGTH(verified_lines) <= 20")));
        assertNotEquals(
                canonical("JSON_TYPE(verified_lines) = 'ARRAY'"),
                canonical("JSON_TYPE(verified_lines) = 'OBJECT'"));
        assertNotEquals(
                canonical("JSON_LENGTH(verified_lines) <= 20"),
                canonical("JSON_LENGTH(verified_lines) <= 21"));
        assertNotEquals(
                canonical("JSON_LENGTH(verified_lines) <= 20"),
                canonical("LENGTH(verified_lines) <= 20"));
        assertThrows(IllegalStateException.class, () -> canonical("JSON_LENGTH(a,b) > 0"));
        assertThrows(IllegalStateException.class, () -> canonical("JSON_EXTRACT(a) = 1"));
    }
}
