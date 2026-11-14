package ai.neargo.shop.spi.trade;

import java.util.List;

/**
 * Finance reads scoped original refunds and resumes their existing execution.
 * Unconfirmed reversal remains visible for manual review; payout recovery is handled separately.
 */
public interface RefundSplitBackPort {

    /** Pending original refunds: arbitration decisions or confirmed execution stuck before reversal. */
    List<PendingBack> pendingSplitBacks();

    /** Resume the original approved refund; acceptance is never proof of reversal or cash arrival. */
    void resumeRefund(String afterSaleNo, String operatorNo);

    /** Scoped source read, including terminal cases after recovery; absence is never proof of cash success. */
    PendingBack find(String afterSaleNo);

    /**
     * @param merchantName 商家名。运营认的是店名，不是 {@code entityNo}
     * @param liability    裁定的责任方 PLATFORM / MERCHANT / PICKUP；未裁决为空
     * @param verdict      裁决说明（取自售后时间线上平台那条）。用户与商家都会看到
     * @param images       用户提交的举证材料。<b>可能为空列表，但不会是 null</b> ——
     *                     运营端契约把它声明成必填数组，缺了会在渲染时抛异常
     */
    record PendingBack(String afterSaleNo, String subOrderNo, String orderNo,
                       String merchantNo, String merchantName, String buyerNickname,
                       String type, String status, long refundMinor, String reason,
                       List<String> images, String liability, String verdict,
                       long createdAt, Boolean splitReversed, ExecutionProgress refundExecution) {
    }
    record ExecutionProgress(String executionNo, String paymentNo, String status, String phase,
                             String errorCode, String resourceStatus, String resourceErrorCode,
                             Long succeededAt, Long nextAttemptAt) { }

}
