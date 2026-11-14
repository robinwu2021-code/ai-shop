package ai.neargo.commerce.api.mall;

import ai.neargo.commerce.api.common.StoreRef;

/** Operator-owned idempotent quote of the original source intent at current business time. */
public record RefundReviewQuoteCommand(
        StoreRef store,
        String orderNo,
        String refundNo,
        String requestDigest,
        String reviewQuoteNo) {}
