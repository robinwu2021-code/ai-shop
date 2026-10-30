package ai.neargo.shop.beauty.dto;

import java.time.Instant;

/**
 * A merchant declaration is displayed without implying bank verification or exposing cashier
 * evidence.
 */
public record ManualReceiptSummaryVO(
        String declarationNo,
        String method,
        long declaredMinor,
        String currency,
        Instant receivedAt,
        Instant recordedAt,
        String provenance,
        String financialVerification) {}
