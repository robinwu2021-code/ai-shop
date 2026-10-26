package ai.neargo.shop.pay.channel.api;

/**
 * 处理器 / 传输层抛出的「发送这件事出了什么问题」。由 {@code OutcomeClassifier} 归入四态（渠道接入统一封装设计 §8.2），
 * 处理器自己不判断「这算失败还是未知」。
 */
public class ChannelTransportException extends RuntimeException {

    public enum Kind {
        /** 请求没发出去（连接拒绝、DNS）—— 渠道一定没收到 */
        CONNECT_FAILED,
        /** 连上之后超时 / 断连 —— 渠道可能已处理 */
        TIMEOUT,
        /** HTTP 5xx / 网关错误 —— 渠道可能已处理 */
        SERVER_ERROR,
        /** HTTP 4xx，带渠道码 */
        CLIENT_ERROR,
        /** 响应验签失败 —— 可能被篡改或渠道改版 */
        BAD_SIGNATURE,
        /** 响应无法解析 */
        UNPARSEABLE
    }

    private final Kind kind;
    private final int httpStatus;
    private final String channelCode;

    public ChannelTransportException(Kind kind, int httpStatus, String channelCode, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.channelCode = channelCode;
    }

    public ChannelTransportException(Kind kind, String message) {
        this(kind, 0, null, message, null);
    }

    public Kind kind() {
        return kind;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String channelCode() {
        return channelCode;
    }
}
