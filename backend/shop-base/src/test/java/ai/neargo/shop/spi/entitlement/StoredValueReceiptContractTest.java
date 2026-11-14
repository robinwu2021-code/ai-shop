package ai.neargo.shop.spi.entitlement;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
class StoredValueReceiptContractTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void postingReceiptRoundTripsActualPrincipalBonusAndBalance() {
        var event=new EntitlementEvents.StoredValueReceiptConfirmed("E1","MAIN","R1","A1","M1","S1",80,25,1105,"JPY",1700000000000L);
        var encoded=PayEntitlementEventCodec.encode(event,json);
        assertEquals(event,PayEntitlementEventCodec.decode(encoded,json));
        assertEquals(80,event.principalAmountMinor()); assertEquals(25,event.giftAmountMinor());
        assertTrue(encoded.payload().contains("balanceAfterMinor"));
    }
    @Test void originalCreditEventRetainsItsExistingCanonicalShape() {
        var legacy=new EntitlementEvents.StoredValueCredited("E1","R1","A1","U1","M1","S1",100,120,"CNY",1700000000000L);
        var wire=PayEntitlementEventCodec.encode(legacy,json);
        assertEquals(legacy,PayEntitlementEventCodec.decode(wire,json));
        assertFalse(wire.payload().contains("balanceAfterMinor"));
        assertFalse(wire.payload().contains("giftAmountMinor"));
    }
    @Test void missingStoreWrongTenantOrImpossiblePostingAmountsNeverBecomeReceipts() {
        assertThrows(IllegalArgumentException.class,()->PayEntitlementEventCodec.encode(
                new EntitlementEvents.StoredValueReceiptConfirmed("E1","MAIN","R1","A1","M1",null,80,25,1105,"CNY",1700000000000L),json));
        assertThrows(IllegalArgumentException.class,()->PayEntitlementEventCodec.encode(
                new EntitlementEvents.StoredValueReceiptConfirmed("E1","OTHER","R1","A1","M1","S1",80,25,1105,"CNY",1700000000000L),json));
        assertThrows(IllegalArgumentException.class,()->PayEntitlementEventCodec.encode(
                new EntitlementEvents.StoredValueReceiptConfirmed("E1","MAIN","R1","A1","M1","S1",80,25,100,"CNY",1700000000000L),json));
    }
}
