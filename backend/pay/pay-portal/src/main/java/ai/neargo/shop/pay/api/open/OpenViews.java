package ai.neargo.shop.pay.api.open;

import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.acquiring.entity.PayRefund;
import ai.neargo.shop.pay.openapp.OpenSensitiveData;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/** 开放 API 的出入参（接口清单 §2）。出参只给第三方该看的列：没有内部 id、收款商户、密钥版本 */
final class OpenViews {

    private OpenViews() {
    }

    /** @param payer 付款人（phone / email / name，选填，x-sensitive）：守卫已解成明文，落库前封存（M3.5） */
    record CreateOrderReq(String outOrderNo, String storeRef, Long amountMinor, String currency, String subject,
                          Integer expireMinutes, String bizNo, Map<String, String> payer) {
    }

    record StartReq(String payMethod, String payerOpenId, String payerAppId) {
    }

    record CloseReq(String reason) {
    }

    /** @param refundAccount 线下退款收款账号（bankCode / accountNo / accountName，x-sensitive）：同上 */
    record RefundReq(String outRefundNo, Long amountMinor, String reason, Map<String, String> refundAccount) {
    }

    /** @param payer 付款人敏感信息的掩码（只回掩码，不回密文、不回明文；M3.5） */
    record OrderView(String payOrderNo, String outOrderNo, String status, long amountMinor, long paidMinor,
                     long refundedMinor, String currency, String market, String payChannel, String payMethod,
                     Long expireAt, Long succeededAt, Long closedAt, String checkoutUrl, Map<String, String> payer) {

        /** @param checkoutUrl 只有建单时给（带一次性令牌）；查单与关单为空 */
        static OrderView of(PayOrder o, String checkoutUrl) {
            return new OrderView(o.getPayOrderNo(), o.getOutOrderNo(), o.getStatus(), nz(o.getAmountMinor()),
                    nz(o.getPaidMinor()), nz(o.getRefundedMinor()), o.getCurrency(), o.getMarket(), o.getPayChannel(),
                    o.getPayMethod(), epoch(o.getExpireAt()), epoch(o.getSucceededAt()), epoch(o.getClosedAt()),
                    checkoutUrl, OpenSensitiveData.masked(o.getPayerMasked()));
        }
    }

    /** @param refundAccount 收款账号的掩码（M3.5） */
    record RefundView(String payRefundNo, String outRefundNo, String status, long amountMinor, String currency,
                      Long succeededAt, Map<String, String> refundAccount) {

        static RefundView of(PayRefund r) {
            return new RefundView(r.getPayRefundNo(), r.getOutRefundNo(), r.getStatus(), nz(r.getAmountMinor()),
                    r.getCurrency(), epoch(r.getSucceededAt()), OpenSensitiveData.masked(r.getRefundAccountMasked()));
        }
    }

    record PayMethodView(String payMethod, String payChannel, String accessMode) {
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    /** 时间一律给 UTC 毫秒（对外不暴露时区假设） */
    private static Long epoch(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
