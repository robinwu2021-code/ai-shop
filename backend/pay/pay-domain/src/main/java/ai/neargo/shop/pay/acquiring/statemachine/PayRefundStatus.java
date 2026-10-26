package ai.neargo.shop.pay.acquiring.statemachine;

/** 退款单状态（{@code pay_refund.status}）。APPROVED 只能来自「未命中规则」或「第二人通过」 */
public enum PayRefundStatus {
    REQUESTED, PENDING_REVIEW, PENDING_SECOND_REVIEW, APPROVED, DISPATCHED, SUCCEEDED, FAILED, REJECTED,
    OFFLINE_PENDING_PROOF;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == REJECTED;
    }
}
