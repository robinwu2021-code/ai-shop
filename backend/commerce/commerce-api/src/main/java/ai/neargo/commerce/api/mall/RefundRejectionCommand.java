package ai.neargo.commerce.api.mall;

import ai.neargo.commerce.api.common.StoreRef;

/** Human decision on the exact immutable REQUESTED intent, never provider failure evidence. */
public record RefundRejectionCommand(
        StoreRef store,
        String orderNo,
        String refundNo,
        String requestDigest,
        String reviewEvidence) {}
