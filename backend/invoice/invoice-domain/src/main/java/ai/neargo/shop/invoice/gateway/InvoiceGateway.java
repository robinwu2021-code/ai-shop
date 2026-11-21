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

    /** @param error 失败原因（通道原文，截断到 512 字），成功为空 */
    record GatewayReceipt(boolean accepted, String gatewayRef, String error) {}

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
