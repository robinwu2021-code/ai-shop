package ai.neargo.commerce.api.port;

import java.util.Optional;
import ai.neargo.commerce.api.common.*;

public record InventoryOutcome(String orderComponentNo, AsyncStatus status, long reservedQuantity, long shippedQuantity, Optional<String> errorCode) {}
