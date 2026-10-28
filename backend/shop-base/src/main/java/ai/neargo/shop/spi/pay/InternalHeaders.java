package ai.neargo.shop.spi.pay;

/**
 * 内部调用的请求头（TDD-支付服务-接入守卫模块pay-guard §2.7.4）：两个方向（业务 → 支付、支付 → 业务）同一套。
 * 发起方由 {@code CallSourceClientInterceptor} 统一挂上，接收方的守卫读出来记录 —— <b>只识别、不校验</b>（G9）。
 */
public final class InternalHeaders {

    /** 调用方码：MALL / FOOD（支付侧按 {@code pay_app.app_no} 取 id）或服务码 SHOP_APP / PAY_SVC … */
    public static final String CALLER = "X-Internal-Caller";
    /** 调用方凭据（服务端只存 SHA-256）；仅接收方打开 {@code verify-credential} 时才看 */
    public static final String CREDENTIAL = "X-Internal-Credential";
    /** {@link CallSource}，经 {@link CallSourceCodec} 编码 */
    public static final String CALL_SOURCE = "X-Call-Source";

    private InternalHeaders() {
    }
}
