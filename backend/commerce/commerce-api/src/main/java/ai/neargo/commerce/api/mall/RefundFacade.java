package ai.neargo.commerce.api.mall;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.fulfillment.FulfillmentQuery;
import ai.neargo.commerce.api.port.VerifiedRefundFact;

/**
 * Buyer requests are separate from independently authorized OPERATOR review and trusted provider
 * facts. Preview amounts are ceilings, never automatic payout authority. Used rights and
 * unknown/legacy SKU policies require manual review. An ordinary paid order with no materialized
 * digital source supports only its complete proven pending digital task group; it refunds digital
 * cash and releases original reserved credit on verified success, without issuing conversion
 * credit. Hardware allocations are excluded. Buyer refund numbers use bounded ASCII
 * letters/digits/colon/dot/underscore/hyphen, begin with a letter or digit, and cannot use the
 * case-insensitive reserved late- prefix.
 */
public interface RefundFacade {
    RefundPreviewView preview(ActorContext actor, RefundPreviewCommand command);

    RefundView request(ActorContext actor, RefundCommand command);

    RefundView approve(ActorContext operator, RefundApprovalCommand command);

    /** Trusted worker boundary; never expose unverified provider facts through a buyer route. */
    RefundView recover(RefundQuery query);

    RefundView confirmRefund(VerifiedRefundFact fact);

    /**
     * Trusted no-rights late-payment compensation. After success, run FulfillmentFacade.recover for
     * this same order to resolve its durable unshipped hardware release fences. Unknown inventory
     * remains retryable and does not imply released stock.
     */
    RefundView recoverLatePayment(FulfillmentQuery query);
}
