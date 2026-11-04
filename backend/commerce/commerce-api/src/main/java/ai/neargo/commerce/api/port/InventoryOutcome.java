package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.common.*;

import java.util.Optional;

/**
 * reserved/shipped are aggregate diagnostics. Only matching operationNo/operationQuantity prove a
 * send.
 */
public record InventoryOutcome(
        String orderComponentNo,
        AsyncStatus status,
        long reservedQuantity,
        long shippedQuantity,
        Optional<String> errorCode,
        String operationNo,
        long operationQuantity) {
    public InventoryOutcome(
            String c, AsyncStatus s, long reserved, long shipped, Optional<String> error) {
        this(c, s, reserved, shipped, error, null, 0);
    }
}
