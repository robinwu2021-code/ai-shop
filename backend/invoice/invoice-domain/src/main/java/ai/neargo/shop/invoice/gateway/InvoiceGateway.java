package ai.neargo.shop.invoice.gateway;

/**
 * 开票通道 SPI（加值中心 / Turnkey / stub）。实现放 invoice-channel，每家一个包。
 *
 * <p>只做协议：把税制生成好的报文送出去，回一句收没收到。不判业务、不改发票状态 —— 状态由
 * {@code InvoiceTransmissionServiceImpl} 按回执推进，同一个报文失败后原样补传。
 */
public interface InvoiceGateway {

    /** 与 {@code ivc_regime_profile.gateway_code} 对应。 */
    String code();

    GatewayReceipt send(GatewayRequest request);

    /**
     * @param refNo   业务号（发票号 / 折让号），通道可拿来做对方系统里的订单号
     * @param account 主体在服务商那边的账号；只有 {@link InvoiceProviderGateway} 会收到非空值
     */
    record GatewayRequest(String regimeCode, String messageType, String refNo, String payloadJson,
            ProviderAccount account) {}

    /**
     * 通道回执。失败分三种，上传任务据此决定下一步（TDD-发票模块 §12）：
     * <ul>
     *   <li>{@link Outcome#RETRYABLE}：对方系统忙 / 网络不通，退避后原样重发</li>
     *   <li>{@link Outcome#ACCOUNT_PROBLEM}：账号认证失败 —— 重发多少次都一样，暂停该主体的上传，等商家核验账号</li>
     *   <li>{@link Outcome#FINAL}：报文被拒（格式、业务规则）—— 不自动重发，标为待人工处理</li>
     * </ul>
     *
     * @param error 失败原因（通道原文，截断到 512 字），成功为空
     */
    record GatewayReceipt(Outcome outcome, String gatewayRef, String error) {

        public enum Outcome { ACCEPTED, RETRYABLE, ACCOUNT_PROBLEM, FINAL }

        public boolean accepted() {
            return outcome == Outcome.ACCEPTED;
        }

        public static GatewayReceipt accepted(String gatewayRef) {
            return new GatewayReceipt(Outcome.ACCEPTED, gatewayRef, null);
        }

        public static GatewayReceipt retryable(String error) {
            return new GatewayReceipt(Outcome.RETRYABLE, null, error);
        }

        public static GatewayReceipt accountProblem(String error) {
            return new GatewayReceipt(Outcome.ACCOUNT_PROBLEM, null, error);
        }

        public static GatewayReceipt rejected(String error) {
            return new GatewayReceipt(Outcome.FINAL, null, error);
        }
    }

    /**
     * 主体在加值中心的账号。{@code accountCode} 是服务商分给商家的代号（鲸跃 officeid），存业务表；
     * {@code user} / {@code password}（鲸跃 posno / posid）只存在 secret 模块，用时现解。
     */
    record ProviderAccount(String accountCode, String user, String password) {
        @Override
        public String toString() {
            return "ProviderAccount[" + accountCode + "]";
        }
    }
}
