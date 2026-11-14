package ai.neargo.shop.beauty.dto;

import java.time.OffsetDateTime;

/** Omitted time means now; storeNo narrows the trusted current store and cannot grant a different store. */
public record StorePolicyQuery(String storeNo, OffsetDateTime at) { }
