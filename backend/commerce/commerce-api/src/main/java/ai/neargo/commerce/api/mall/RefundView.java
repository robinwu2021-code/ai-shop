package ai.neargo.commerce.api.mall;
import ai.neargo.commerce.api.common.*;
/** reviewStatus distinguishes human DECLINED from provider REJECTED; money status alone does not. */
public record RefundView(String refundNo, String orderNo, AsyncStatus status, Money cashAmount,
        Money restoredCredit, String reviewStatus, String requestDigest, String reviewQuoteNo) {
    public RefundView(String refundNo, String orderNo, AsyncStatus status, Money cashAmount, Money restoredCredit) {
        this(refundNo, orderNo, status, cashAmount, restoredCredit, null, null, null);
    }
}
