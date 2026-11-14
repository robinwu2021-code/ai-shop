package ai.neargo.shop.pay.acquiring.dto;

/** 发起退款（商户侧：商户端、本店刷卡机、授权的第三方、内部系统代商户操作员） */
public record RefundRequestCmd(String payOrderNo, String outRefundNo, String bizRefundNo, long amountMinor,
                               String reason) {
}
