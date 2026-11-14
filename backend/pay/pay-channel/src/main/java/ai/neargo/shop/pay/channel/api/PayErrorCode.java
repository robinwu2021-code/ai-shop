package ai.neargo.shop.pay.channel.api;

/**
 * 统一错误码（渠道接入统一封装设计 §13.1）。渠道原始码经 {@code ChannelErrorMapper} 落到这里。
 * 分段：PARAM_ 参数 · CHANNEL_ 渠道 · REFUND_ 退款 · SYSTEM_ 系统。
 */
public enum PayErrorCode {
    PARAM_INVALID,
    PARAM_AMOUNT_SCALE,
    CHANNEL_UNSUPPORTED,
    CHANNEL_DISABLED,
    CHANNEL_THROTTLED,
    CHANNEL_UNAVAILABLE,
    CHANNEL_BUSY,
    CHANNEL_REJECTED,
    CHANNEL_CONNECT_FAILED,
    /** 渠道说没有这笔 —— 与「查不通」不同：前者可以安全关单 */
    TRADE_NOT_FOUND,
    REFUND_REJECTED,
    /** 渠道把这笔退款关了（微信 {@code CLOSED}）：终态，钱没退出去 */
    REFUND_CLOSED,
    /** 渠道说退款异常（微信 {@code ABNORMAL}：收款方账户异常等），要人工处理 */
    REFUND_ABNORMAL,
    SYSTEM_ERROR
}
