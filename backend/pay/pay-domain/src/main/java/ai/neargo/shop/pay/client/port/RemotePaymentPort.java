package ai.neargo.shop.pay.client.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.client.PaymentInternalApi;
import ai.neargo.shop.pay.client.PortResult;
import ai.neargo.shop.spi.pay.CallSource;
import ai.neargo.shop.spi.pay.CallSourceCodec;
import ai.neargo.shop.spi.pay.CallSources;
import ai.neargo.shop.spi.pay.PaymentPort;
import ai.neargo.svc.client.ServiceCalls;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 独立形态的 {@link PaymentPort}（代码清单 §5.3）：经 pay-svc 的支付单内部端点调。与内嵌的 {@code PaymentPortImpl}
 * 同一时刻只装一个（由 shop-app {@code payclient} 按形态选装，支付域自己不知道跑在哪种形态里）。
 *
 * <p>业务拒绝原样还原成同一个 {@link BizException}（{@link PortResult}）—— 调用方不必知道支付在不在同一个进程里，
 * {@code PaymentPortLocalRemoteParityTest} 钉着两种实现对同一输入的结果一致。
 */
public class RemotePaymentPort implements PaymentPort {

    private final PaymentInternalApi api;
    /** 内部 app 码 → 该调用方的凭据（部署环境给，不进仓库） */
    private final Map<String, String> credentials;
    private final String service;

    public RemotePaymentPort(PaymentInternalApi api, Map<String, String> credentials, String service) {
        this.api = api;
        this.credentials = Map.copyOf(credentials);
        this.service = service;
    }

    @Override
    public PaymentHandle createPayment(InternalCreatePaymentCmd cmd) {
        String app = appOf(cmd.bizType());
        return unwrap(() -> api.createPayment(app, credential(app), header(cmd.source()), cmd));
    }

    @Override
    public PaymentHandle queryPayment(String bizType, String payOrderNo, CallSource source) {
        String app = appOf(bizType);
        return unwrap(() -> api.queryPayment(app, credential(app), header(source),
                new PaymentInternalApi.Query(bizType, payOrderNo)));
    }

    @Override
    public PaymentHandle closePayment(String bizType, String outOrderNo, String reason, CallSource source) {
        String app = appOf(bizType);
        return unwrap(() -> api.closePayment(app, credential(app), header(source),
                new PaymentInternalApi.Close(bizType, outOrderNo, reason)));
    }

    @Override
    public RefundHandle requestRefund(InternalRefundCmd cmd) {
        String app = appOf(cmd.bizType());
        return unwrap(() -> api.requestRefund(app, credential(app), header(cmd.source()), cmd));
    }

    /** 业务类型 → 内部 app 码（与 pay-domain {@code PaymentPortImpl#appOf} 同一张表） */
    public static String appOf(String bizType) {
        return PaymentPort.FOOD_CHECK.equals(bizType) ? "FOOD" : "MALL";
    }

    /** 命令里带的来源编成头（来源不进请求体）；万一为空取当前上下文 */
    private static String header(CallSource source) {
        return CallSourceCodec.encode(source != null ? source : CallSources.current());
    }

    private String credential(String app) {
        String c = credentials.get(app);
        if (c == null || c.isBlank()) {
            throw new IllegalStateException("PAY_INTERNAL_CALLER_NOT_CONFIGURED: " + app);
        }
        return c;
    }

    private <T> T unwrap(Supplier<PortResult<T>> call) {
        PortResult<T> r = ServiceCalls.call(service, call);
        if (r.errorCode() != null) {
            throw BizException.of(ErrorCode.valueOf(r.errorCode()), r.args() == null ? new Object[0] : r.args().toArray());
        }
        return r.value();
    }
}
