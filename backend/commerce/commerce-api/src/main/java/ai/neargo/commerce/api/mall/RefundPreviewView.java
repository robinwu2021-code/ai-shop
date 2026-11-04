package ai.neargo.commerce.api.mall;

import ai.neargo.commerce.api.common.*;

import java.time.Instant;
import java.util.List;

/** Limits are frozen ceilings, not an approved cash-refund instruction. */
public record RefundPreviewView(
        String refundNo,
        String orderNo,
        Money cashLimit,
        Money creditLimit,
        boolean requiresManualReview,
        List<String> sourcePeriodNos,
        Instant expiresAt,
        String approvalDigest) {
    public RefundPreviewView {
        sourcePeriodNos = List.copyOf(sourcePeriodNos);
    }
}
