package ai.neargo.commerce.api.mall;

import ai.neargo.commerce.api.common.Money;

import java.time.Instant;

/**
 * Frozen valuation, not a balance query at render time. New conversion C and applied credit U
 * differ.
 */
public record QuoteCreditView(
        Money newConversion,
        Money historicalAvailable,
        Money applied,
        Money reservedHistorical,
        Money balanceAfter,
        Instant valuationAt) {}
