package ai.neargo.shop.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.lang.reflect.InvocationTargetException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StoreRequestScopeValidatorTest {
    private void check(String entity, String store, String targetEntity, String targetStore,
            String header, Set<String> stores) {
        Class<?> type = assertDoesNotThrow(() -> Class.forName("ai.neargo.shop.auth.StoreRequestScopeValidator"));
        try {
            type.getMethod("requireStoreTarget", String.class, String.class, String.class, String.class,
                    String.class, Set.class).invoke(null, entity, store, targetEntity, targetStore, header, stores);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException cause) { throw cause; }
            throw new AssertionError(ex.getCause());
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }

    @Test
    void permitsTrustedDefaultOnlyWhenHeaderAbsent() {
        assertDoesNotThrow(() -> check("E", "A", "E", "A", null, Set.of("A")));
        assertDoesNotThrow(() -> check("E", "A", "E", "A", "A", Set.of("A")));
    }

    @Test
    void rejectsExplicitBlankOrInvalidHeaderAfterFallback() {
        for (String header : new String[]{"", " ", "OTHER"}) {
            var error = assertThrows(BizException.class, () -> check("E", "A", "E", "A", header, Set.of("A")));
            assertEquals(ErrorCode.FORBIDDEN, error.errorCode());
        }
    }

    @Test
    void rejectsCrossEntityAndCrossStoreEvenWhenStoreAuthorized() {
        assertThrows(BizException.class, () -> check("E", "A", "FOREIGN", "A", "A", Set.of("A")));
        assertThrows(BizException.class, () -> check("E", "A", "E", "B", "B", Set.of("A", "B")));
    }

    @Test
    void rejectsMissingFactsOrAuthorization() {
        assertThrows(BizException.class, () -> check(null, "A", "E", "A", null, Set.of("A")));
        assertThrows(BizException.class, () -> check("E", "A", "E", null, null, Set.of("A")));
        assertThrows(BizException.class, () -> check("E", "A", "E", "A", null, null));
        assertThrows(BizException.class, () -> check("E", "A", "E", "A", null, Set.of("B")));
    }
}
