package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.common.*;

public record NotificationAudienceQuery(StoreRef store, String entitlementCode) {}
