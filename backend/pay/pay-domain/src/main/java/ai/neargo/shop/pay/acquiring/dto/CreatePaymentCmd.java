package ai.neargo.shop.pay.acquiring.dto;

import java.time.Instant;

/**
 * 建单命令（三条路径共用；内部调用方由 {@code PaymentPortImpl} 从 SPI 命令转来）。
 *
 * @param market 市场码；币种必须是该市场的收款币种（I5 开放 API 起按 {@code pay_channel_product_market} 校验）
 */
public record CreatePaymentCmd(String bizType, String bizNo, String outOrderNo, Long storeId, String storeName,
                               String entityName, String market, long amountMinor, String currency, String subject,
                               String scene, Instant expireAt) {
}
