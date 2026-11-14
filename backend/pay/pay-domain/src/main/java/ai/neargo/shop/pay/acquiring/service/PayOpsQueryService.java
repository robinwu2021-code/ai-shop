package ai.neargo.shop.pay.acquiring.service;

import java.util.List;

/**
 * 支付运营端的只读视图（TDD-支付服务-I5接入层 S9，F50 F51 F53）：支付单列表与「四向时间线」、待复核退款、答复记录、支付异常。
 * 运营是平台身份，不按门店圈；写操作（复核、重推、处置）不在这里，走各自的服务。
 */
public interface PayOpsQueryService {

    /** 按条件翻支付单，id 倒序。条件都可空；{@code payOrderNo} / {@code outOrderNo} 精确匹配 */
    List<OrderRow> orders(OrderFilter filter, int page, int size);

    /** 一张支付单的全貌：交易尝试、退款单、流水凭证、答复记录、支付异常。没有这张单为空 */
    OrderDetail detail(String payOrderNo);

    List<RefundRow> refunds(String status, int limit);

    List<NotifyRow> notifies(String status, int limit);

    List<ExceptionRow> exceptions(String status, int limit);

    record OrderFilter(String status, String payOrderNo, String outOrderNo, Long storeId, String bizType) {
    }

    record OrderRow(String payOrderNo, String outOrderNo, String bizType, Long storeId, String storeName, String market,
                    String currency, long amountMinor, long paidMinor, long refundedMinor, String status, String payChannel,
                    String payMethod, String fundsPath, String sourceType, Long createdAt, Long succeededAt) {
    }

    record OrderDetail(OrderRow order, List<AttemptRow> attempts, List<RefundRow> refunds, List<VoucherRow> vouchers,
                       List<NotifyRow> notifies, List<ExceptionRow> exceptions) {
    }

    /** @param at 成功时间（UTC 毫秒），未成功为空 */
    record AttemptRow(String paymentNo, String direction, String outTradeNo, String tradeNo, String payChannel,
                      long amountMinor, String currency, String status, Long payMerchantId, String fundsPath, Long at) {
    }

    record RefundRow(String payRefundNo, String payOrderNo, long amountMinor, String currency, String status,
                     String reason, String requestedBy, String sourceType, String hitReasons, Long createdAt) {
    }

    record VoucherRow(String voucherNo, String voucherType, long totalMinor, String currency, String fundsPath,
                      String bizDate, String memo) {
    }

    record NotifyRow(Long id, String eventType, String eventKey, String targetType, String targetUrl, String status,
                     int attempts, Long nextAt, Long deliveredAt, Long createdAt) {
    }

    record ExceptionRow(Long id, String kind, String payOrderNo, long amountMinor, String currency, String status,
                        String resolution, String note, String handledBy, Long createdAt) {
    }
}
