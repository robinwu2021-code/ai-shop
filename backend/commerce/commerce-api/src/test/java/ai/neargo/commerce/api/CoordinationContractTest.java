package ai.neargo.commerce.api;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.port.*;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

class CoordinationContractTest {
    @Test
    void legacyOutcomesNeverInventClosureOrOperationProof() {
        var payment =
                new PaymentOutcome(
                        "order",
                        Optional.empty(),
                        "platform",
                        new Money("CNY", 1),
                        AsyncStatus.FAILED,
                        Optional.empty(),
                        Optional.of("NOT_FOUND"));
        assertFalse(payment.definitivelyClosed());
        assertTrue(payment.closureReference().isEmpty());
        var inventory =
                new InventoryOutcome("component", AsyncStatus.SUCCEEDED, 2, 2, Optional.empty());
        assertNull(inventory.operationNo());
        assertEquals(0, inventory.operationQuantity());
        assertEquals(
                "order",
                new PaymentIntentCommand(
                                "tenant", "order", "platform", new Money("CNY", 1), Instant.EPOCH)
                        .paymentAttemptNo());
        assertEquals("component", new InventoryQuery("tenant", "component").operationNo());
    }

    @Test
    void invalidInventoryUnitsAreRejectedBeforeAnyBridgeCanReceiveThem() {
        for (long q :
                new long[] {Long.MIN_VALUE, -1, 0, (long) Integer.MAX_VALUE + 1, Long.MAX_VALUE})
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new InventoryCommand(
                                    "tenant", "order", "component", "sku", q, "operation"));
        assertEquals(
                Integer.MAX_VALUE,
                new InventoryCommand(
                                "tenant",
                                "order",
                                "component",
                                "sku",
                                Integer.MAX_VALUE,
                                "operation")
                        .quantity());
    }

    @Test
    void checkoutParametersAreCopiedAndImmutable() {
        var params = new HashMap<String, String>();
        params.put("token", "public-client-launch-data");
        var r =
                new PaymentOutcome(
                        "order",
                        Optional.of("pay"),
                        "platform",
                        new Money("CNY", 1),
                        AsyncStatus.PENDING,
                        Optional.empty(),
                        Optional.empty(),
                        params,
                        false,
                        Optional.empty());
        params.clear();
        assertEquals("public-client-launch-data", r.checkoutParameters().get("token"));
        assertThrows(UnsupportedOperationException.class, () -> r.checkoutParameters().clear());
    }
}
