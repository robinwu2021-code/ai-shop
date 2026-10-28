package ai.neargo.shop.pay.client;

/**
 * 支付单内部端点（开发计划 §3.1；TDD-支付服务-I3支付单退款流水 S8）。与旧的共享令牌端点分开：这一组按<b>调用方</b>鉴权
 * （{@link #CALLER} + {@link #CREDENTIAL}），并按调用方限定业务类型 —— 餐饮调不了商城的退款。
 */
public final class PaymentInternalPaths {

    public static final String PREFIX = "/internal/pay/v1";
    public static final String PAYMENTS = PREFIX + "/payments";
    public static final String PAYMENT_QUERY = PREFIX + "/payments/query";
    public static final String PAYMENT_CANCEL = PREFIX + "/payments/cancel";
    public static final String PAYMENT_CLOSE = PREFIX + "/payments/close";
    public static final String REFUNDS = PREFIX + "/refunds";

    /** 内部 app 码（{@code pay_app.app_no}：MALL / FOOD） */
    public static final String CALLER = "X-Internal-Caller";
    /** 该调用方自己的凭据（服务端只存 SHA-256） */
    public static final String CREDENTIAL = "X-Internal-Credential";

    private PaymentInternalPaths() {
    }
}
