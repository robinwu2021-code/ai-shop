package ai.neargo.shop.pay.client;

import java.util.List;

/**
 * 支付单内部端点的应答：成功带值，业务拒绝带错误码与参数（{@code PAY_REFUND_EXCEEDS} 之类）。
 * 业务拒绝不用 HTTP 状态表达 —— 客户端的统一状态处理会丢掉应答体，错误码就过不了边界，内嵌与独立的行为就不一致了。
 * 鉴权失败（401）、调用方越权（403）仍是 HTTP 状态。
 */
public record PortResult<T>(T value, String errorCode, List<String> args) {

    public static <T> PortResult<T> ok(T value) {
        return new PortResult<>(value, null, List.of());
    }

    public static <T> PortResult<T> rejected(String errorCode, List<String> args) {
        return new PortResult<>(null, errorCode, args);
    }
}
