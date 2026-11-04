package ai.neargo.commerce.api.mall;

import ai.neargo.commerce.api.common.*;

/** Exact reviewed quote and evidence are mandatory even for unused digital allocations. */
public record RefundApprovalCommand(
        StoreRef store,
        String orderNo,
        String refundNo,
        String approvalDigest,
        Money cashAmount,
        Money restoreCredit,
        String reviewEvidence) {}
