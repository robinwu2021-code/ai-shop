package ai.neargo.shop.trade.dto;

import java.util.List;

/** 售后单（对齐契约 AfterSale）。 */
public record AfterSaleVO(String afterSaleNo,
                          String subOrderNo,
                          String orderNo,
                          String type,
                          String status,
                          String reason,
                          List<String> images,
                          long refundMinor,
                          /** 极速退：命中阈值自动通过，商家只可见不可拒 */
                          boolean instant,
                          /**
                           * 商家同意/驳回时的说明。
                           *
                           * <p><b>叫 merchantReply 而不是库里那个 merchant_remark</b>：
                           * 两个端的契约与文案都用前者（C 端订单页「商家回复：」直接读它）。
                           * 此前发的是列名，于是 <b>C 端那句「商家回复」永远不显示</b> ——
                           * `v-if` 拿到 undefined 就整块不渲染，不报错、看着像商家没回。
                           */
                          String merchantReply,
                          /** 用户寄回的运单号（RETURN_REFUND）。契约里叫 returnExpressNo */
                          String returnExpressNo,
                          /** 责任方，平台裁决后才有（P-6.1.4，口径未定） */
                          String liability,
                          long createdAt,
                          /**
                           * 最后一次状态变更时间。
                           *
                           * <p>此前只发 `createdAt`，而两个端的列表都按「最后动了什么时候」排/显示 ——
                           * B 端售后页每一行的时间因此是 `NaN-NaN-NaN NaN:NaN`
                           * （契约里声明了 updatedAt，后端根本没这个字段）。
                           * 超时自动同意之类的时效规则也以它为基准。
                           */
                          long updatedAt,
                          List<TimelineNode> timeline,
                          /**
                           * 同意这一笔之后<b>会一并退回什么</b>（待办设计 P2c · B 端售后单）。
                           * 只在商家侧、待处理、且这是整单最后一笔时给；其余为 null。
                           * <b>商家券</b>的退回让他少收一次券核销，所以要在点同意之前看见。
                           */
                          RefundImpact impact,
                          /** 退款形式：当前仅受理 {@code CASH}，积分返还由独立退款准备处理 */
                          String refundMode,
                          /** 旧补偿字段；当前现金退款不写入，待接口清理时删除 */
                          Long refundPoints,
                          /** 商家核定的退款额(分)，部分退款时 < 申请额；未核定为 null（= 全额）（AC9） */
                          Long approvedMinor,
                          /** 申请当时的履约进度快照（{@code FulfillStage} 名）。B 端审核弹层显示实况（AC15） */
                          String fulfillStage,
                          /** 是否「待收货后退款」：等价于 {@code status == WAIT_RECEIPT}，端上少判一层（AC7） */
                          boolean waitReceipt,
                          /*
                           * 买家此刻能否「申请平台介入」（后端按状态与商家是否超时判；TDD-订单状态机 AC10）。
                           * 端上按它画按钮，不再各端自己判状态。
                           */
                          boolean canEscalate,
                          RefundExecutionProgress refundExecution) {

    /** Preserve existing producers while progress becomes available on the real refund execution path. */
    public AfterSaleVO(String afterSaleNo, String subOrderNo, String orderNo, String type, String status,
                       String reason, List<String> images, long refundMinor, boolean instant, String merchantReply,
                       String returnExpressNo, String liability, long createdAt, long updatedAt,
                       List<TimelineNode> timeline, RefundImpact impact, String refundMode, Long refundPoints,
                       Long approvedMinor, String fulfillStage, boolean waitReceipt, boolean canEscalate) {
        this(afterSaleNo, subOrderNo, orderNo, type, status, reason, images, refundMinor, instant, merchantReply,
                returnExpressNo, liability, createdAt, updatedAt, timeline, impact, refundMode, refundPoints,
                approvedMinor, fulfillStage, waitReceipt, canEscalate, null);
    }

    /** Original execution and independent resource recovery remain visible after cash confirmation. */
    public record RefundExecutionProgress(String executionNo, String paymentNo, String status, String phase,
                                          String errorCode, String resourceStatus, String resourceErrorCode,
                                          Long succeededAt, Long nextAttemptAt) { }

    /** 整单退款会一并退回的券与积分；开关关掉的那一项为空 / 0 */
    public record RefundImpact(String couponTitle, long pointsReturn, long pointsRevoke) {
        public boolean isEmpty() {
            return (couponTitle == null || couponTitle.isBlank()) && pointsReturn <= 0 && pointsRevoke <= 0;
        }
    }

    public AfterSaleVO withImpact(RefundImpact i) {
        return new AfterSaleVO(afterSaleNo, subOrderNo, orderNo, type, status, reason, images,
                refundMinor, instant, merchantReply, returnExpressNo, liability, createdAt,
                updatedAt, timeline, i == null || i.isEmpty() ? null : i,
                refundMode, refundPoints, approvedMinor, fulfillStage, waitReceipt, canEscalate, refundExecution);
    }

    public record TimelineNode(String status, String label, long at) {
    }
}
