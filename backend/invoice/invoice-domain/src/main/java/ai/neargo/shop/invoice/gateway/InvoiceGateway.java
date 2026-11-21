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

    GatewayReceipt send(String regimeCode, String messageType, String payloadJson);

    /** @param error 失败原因（通道原文，截断到 512 字），成功为空 */
    record GatewayReceipt(boolean accepted, String gatewayRef, String error) {}
}
