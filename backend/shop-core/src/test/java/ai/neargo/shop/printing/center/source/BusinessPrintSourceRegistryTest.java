package ai.neargo.shop.printing.center.source;
import ai.neargo.shop.spi.printing.PrintSourceFactsPort;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class BusinessPrintSourceRegistryTest {
    PrintSourceFactsPort source(String type,String scene) {
        var p=mock(PrintSourceFactsPort.class); when(p.sourceTypes()).thenReturn(Set.of(type)); when(p.scenes()).thenReturn(Set.of(scene)); return p;
    }
    @Test void recoverySelectsRegisteredSourcesForTheExactSceneAndCashierStaysClosed() {
        var registry=new PrintSourceRegistry(List.of(source("SUB_ORDER","ORDER_PAID"),source("REFUND","REFUND_RECEIPT"),source("RECHARGE","RECHARGE_RECEIPT")));
        assertEquals(Set.of("REFUND"),registry.sourceTypesForScene("REFUND_RECEIPT"));
        assertEquals(Set.of("RECHARGE"),registry.sourceTypesForScene("RECHARGE_RECEIPT"));
        assertEquals(Set.of("SUB_ORDER"),registry.sourceTypesForScene("ORDER_PAID"));
        assertFalse(registry.supports("REFUND","RECHARGE_RECEIPT"));
        assertFalse(registry.supports("POS_SETTLEMENT","CASHIER_RECEIPT"));
        assertEquals(Set.of(),registry.sourceTypesForScene("CASHIER_RECEIPT"));
        assertThrows(IllegalStateException.class,()->new PrintSourceRegistry(List.of(source("REFUND","REFUND_RECEIPT"),source("REFUND","OTHER"))));
    }
}
