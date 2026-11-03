package ai.neargo.commerce.api.mall;

import java.time.Instant;
import java.util.List;
import ai.neargo.commerce.api.common.*;

public record QuoteView(String quoteNo, StoreRef store, Money total, Money credit, Money payable, List<QuoteComponent> components, long entitlementVersion, long balanceVersion, Instant expiresAt) {}
