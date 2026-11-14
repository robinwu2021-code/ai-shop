package ai.neargo.commerce.api.subscription;

import java.util.Optional;
import ai.neargo.commerce.api.common.*;

public record SubscriptionQuery(StoreRef store, Optional<String> entitlementCode) {}
