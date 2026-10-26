package ai.neargo.shop.pay.channel.api;

import java.util.Set;

/** 对渠道的操作（渠道接入统一封装设计 §2.1、§8.1） */
public enum ChannelOperation {
    PAY, CONFIRM, QUERY, CLOSE, VOID, REFUND, REFUND_QUERY,
    ONBOARDING_SUBMIT, ONBOARDING_QUERY, MEDIA_UPLOAD, STATEMENT_FETCH, PAYOUT, KEY_VERIFY,
    /** 补差 / 补差回退 / 分账 / 分账回退（{@code SplitCommand.Kind}）：资金动作，与退款同样永不重发 */
    SPLIT;

    private static final Set<ChannelOperation> READ_ONLY = Set.of(QUERY, REFUND_QUERY, ONBOARDING_QUERY, STATEMENT_FETCH);

    /** 纯读：结果不明时可以放心再问一次 */
    public boolean readOnly() {
        return READ_ONLY.contains(this);
    }

    /**
     * 「渠道可能已经处理了」之后能否再发同一命令。
     *
     * <p>只有纯读可以。退款、刷卡、扫码付、confirm、出款重发 = 可能重复扣钱或重复出账；
     * 线上 PAY 虽然渠道按单号去重，也只在<b>请求根本没发出去</b>（连接失败）时才重发一次 ——
     * 那一条由 {@code RetryInterceptor} 按「是否已发出」单独判断，不走这里。
     */
    public boolean resendSafeAfterUncertainty() {
        return readOnly();
    }
}
