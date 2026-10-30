package ai.neargo.shop.idem;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.Test;

class IdempotencyScopeTest {
    private Object call(String method, Class<?>[] parameters, Object... args) {
        var type = assertDoesNotThrow(() -> Class.forName("ai.neargo.shop.idem.IdempotencyScope"));
        try { return type.getMethod(method, parameters).invoke(null, args); }
        catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException cause) { throw cause; }
            throw new AssertionError(ex.getCause());
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }

    private String endpoint(String industry, String operation, String entity, String store) {
        return (String) call("endpoint", new Class<?>[]{String.class, String.class, String.class, String.class},
                industry, operation, entity, store);
    }

    @Test
    void separatesRetailFoodBeautyAndEveryScopeDimension() {
        assertNotEquals(endpoint("RETAIL", "PUBLISH", "E", "S"), endpoint("FOOD", "PUBLISH", "E", "S"));
        assertNotEquals(endpoint("FOOD", "PUBLISH", "E", "S"), endpoint("BEAUTY", "PUBLISH", "E", "S"));
        assertNotEquals(endpoint("BEAUTY", "PUBLISH", "E", "S"), endpoint("BEAUTY", "OTHER", "E", "S"));
        assertNotEquals(endpoint("BEAUTY", "PUBLISH", "E", "S"), endpoint("BEAUTY", "PUBLISH", "F", "S"));
        assertNotEquals(endpoint("BEAUTY", "PUBLISH", "E", "S"), endpoint("BEAUTY", "PUBLISH", "E", "T"));
        assertEquals(74, endpoint("BEAUTY", "PUBLISH", "E", "S").length());
    }

    @Test
    void usesUnambiguousLengthsAndStableUnicode() {
        assertNotEquals(endpoint("BEAUTY", "a:b", "c", "d"), endpoint("BEAUTY", "a", "b:c", "d"));
        assertEquals("RETAIL:v1:421fd787ad37d2c39c10b140e669358ae8496403becdb960653421130cfab7cc",
                endpoint("RETAIL", "发布", "主体", "门店"));
    }

    @Test
    void validatesKeyWithoutTruncatingOrChangingIt() {
        var signature = new Class<?>[]{String.class};
        assertEquals(" key ", call("requireKey", signature, " key "));
        assertEquals("a".repeat(128), call("requireKey", signature, "a".repeat(128)));
        assertThrows(RuntimeException.class, () -> call("requireKey", signature, (Object) null));
        assertThrows(RuntimeException.class, () -> call("requireKey", signature, " "));
        assertThrows(RuntimeException.class, () -> call("requireKey", signature, "a".repeat(129)));
    }
}
