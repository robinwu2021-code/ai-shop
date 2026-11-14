package ai.neargo.commerce.api.mall;

import java.time.Instant;
import java.util.List;
import ai.neargo.commerce.api.common.*;

public record OrderView(String orderNo, StoreRef store, OrderStatus status, Money payable, List<QuoteComponent> components, Instant createdAt) {}
