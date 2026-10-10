package ai.neargo.shop.logistics.capability;

/**
 * 调一次渠道的结局。领域层只认这三种，各家的错误码由渠道自己的码表分类（TDD-物流模块 §2.6）。
 *
 * @param kind    结局
 * @param code    渠道原始码（排查用）
 * @param message 渠道原文
 * @param ref     成功时渠道给的引用（订阅号之类），可空
 */
public record ChannelOutcome(Kind kind, String code, String message, String ref) {

    public enum Kind {
        /** 成功（含「重复订阅」这类等价于成功的） */
        OK,
        /** 可重试：网络、5xx、取 token 失败、限流 —— 同一渠道退避后再来 */
        RETRYABLE,
        /** 不可重试：凭据、余额、不支持的公司、单号错 …… 以及认不出的码（盲目重试会烧额度） */
        FATAL,
        /**
         * 对方还没准备好（微信 9300559：运单还没收录）。<b>不是失败、也不重试</b> —— 等下一次自然触发
         * （下一条推送）再试；重试只会白白耗微信的调用次数。
         */
        NOT_READY
    }

    public static ChannelOutcome ok(String code, String ref) {
        return new ChannelOutcome(Kind.OK, code, null, ref);
    }

    public static ChannelOutcome retryable(String code, String message) {
        return new ChannelOutcome(Kind.RETRYABLE, code, message, null);
    }

    public static ChannelOutcome fatal(String code, String message) {
        return new ChannelOutcome(Kind.FATAL, code, message, null);
    }

    public static ChannelOutcome notReady(String code, String message) {
        return new ChannelOutcome(Kind.NOT_READY, code, message, null);
    }

    public boolean isOk() {
        return kind == Kind.OK;
    }
}
