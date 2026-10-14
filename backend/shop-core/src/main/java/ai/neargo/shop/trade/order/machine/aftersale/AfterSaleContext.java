package ai.neargo.shop.trade.order.machine.aftersale;

import ai.neargo.shop.common.ErrorCode;

/**
 * 售后迁移要读的事实（调用方加载，状态机只读）。字段多，用 {@link Builder} 写，缺省全部「通过」。
 */
public final class AfterSaleContext {

    /** 执行者对这张售后单有权限（本人 / 本店 / 平台处理权限） */
    public final boolean authorized;
    /** 退货退款（否则仅退款） */
    public final boolean returnRefund;
    /** 货在途（已揽收未签收）：仅退款同意后等签收 */
    public final boolean inTransit;
    /** 线下当面付的单：退钱一步改为商家线下退款并登记 */
    public final boolean offlinePay;
    /** 退货已回到商家手里（平台裁决退款时决定是否还要等寄回） */
    public final boolean goodsReturned;
    /** 申请规则（类型 × 履约阶段 × 生鲜）判出的错误；null = 通过 */
    public final ErrorCode applyRuleError;
    public final boolean inWindow;
    public final boolean noOpenAfterSale;
    /** 金额 ≤ 剩余可退额且 > 0 */
    public final boolean amountRefundable;
    /** 渠道能原路退这笔（部分退时须支持部分退、未超次数与期限） */
    public final boolean channelSupports;
    public final boolean reasonValid;
    /** 协商额 > 0 且 < 申请额 */
    public final boolean offerValid;
    public final boolean remarkPresent;
    /** 可申请平台介入：被驳回 7 天内 / 商家超时 / 协商僵持 / 线下退款有异议 */
    public final boolean escalationOpen;
    /** 定时器事件的到期条件已满足 */
    public final boolean expired;
    public final boolean waybillValid;
    public final boolean proofPresent;
    /** 交易不成立的原因 ∈ {拼团未成团, 集单撤单, 关单后迟到回调} */
    public final boolean voidReasonValid;

    private AfterSaleContext(Builder b) {
        authorized = b.authorized; returnRefund = b.returnRefund; inTransit = b.inTransit;
        offlinePay = b.offlinePay; goodsReturned = b.goodsReturned; applyRuleError = b.applyRuleError;
        inWindow = b.inWindow; noOpenAfterSale = b.noOpenAfterSale; amountRefundable = b.amountRefundable;
        channelSupports = b.channelSupports; reasonValid = b.reasonValid; offerValid = b.offerValid;
        remarkPresent = b.remarkPresent; escalationOpen = b.escalationOpen; expired = b.expired;
        waybillValid = b.waybillValid; proofPresent = b.proofPresent; voidReasonValid = b.voidReasonValid;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 缺省全部「通过」、仅退款、线上付、未在途 —— 调用方只改与本次相关的事实 */
    public static final class Builder {
        private boolean authorized = true, returnRefund, inTransit, offlinePay, goodsReturned;
        private ErrorCode applyRuleError;
        private boolean inWindow = true, noOpenAfterSale = true, amountRefundable = true, channelSupports = true;
        private boolean reasonValid = true, offerValid = true, remarkPresent = true, escalationOpen = true;
        private boolean expired = true, waybillValid = true, proofPresent = true, voidReasonValid = true;

        public Builder authorized(boolean v) { authorized = v; return this; }
        public Builder returnRefund(boolean v) { returnRefund = v; return this; }
        public Builder inTransit(boolean v) { inTransit = v; return this; }
        public Builder offlinePay(boolean v) { offlinePay = v; return this; }
        public Builder goodsReturned(boolean v) { goodsReturned = v; return this; }
        public Builder applyRuleError(ErrorCode v) { applyRuleError = v; return this; }
        public Builder inWindow(boolean v) { inWindow = v; return this; }
        public Builder noOpenAfterSale(boolean v) { noOpenAfterSale = v; return this; }
        public Builder amountRefundable(boolean v) { amountRefundable = v; return this; }
        public Builder channelSupports(boolean v) { channelSupports = v; return this; }
        public Builder reasonValid(boolean v) { reasonValid = v; return this; }
        public Builder offerValid(boolean v) { offerValid = v; return this; }
        public Builder remarkPresent(boolean v) { remarkPresent = v; return this; }
        public Builder escalationOpen(boolean v) { escalationOpen = v; return this; }
        public Builder expired(boolean v) { expired = v; return this; }
        public Builder waybillValid(boolean v) { waybillValid = v; return this; }
        public Builder proofPresent(boolean v) { proofPresent = v; return this; }
        public Builder voidReasonValid(boolean v) { voidReasonValid = v; return this; }

        public AfterSaleContext build() {
            return new AfterSaleContext(this);
        }
    }
}
