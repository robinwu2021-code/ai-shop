package ai.neargo.commerce.api.mall;

import java.util.Optional;
import ai.neargo.commerce.api.common.*;

public record PlaceOrderCommand(String quoteNo, String idempotencyKey, Optional<ShippingAddress> shippingAddress) {}
