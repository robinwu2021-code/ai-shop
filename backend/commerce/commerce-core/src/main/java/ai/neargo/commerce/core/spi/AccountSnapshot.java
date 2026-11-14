package ai.neargo.commerce.core.spi;

import ai.neargo.commerce.api.common.StoreRef;

public record AccountSnapshot(
        StoreRef store,
        String entityNo,
        String businessTypeCode,
        long entitlementVersion,
        String pendingChangeOrderNo,
        long rowVersion) {}
