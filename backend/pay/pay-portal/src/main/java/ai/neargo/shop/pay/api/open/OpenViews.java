package ai.neargo.shop.pay.api.open;

import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.acquiring.entity.PayRefund;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 开放 API 的出入参（接口清单 §2）。出参只给第三方该看的列：没有内部 id、收款商户、密钥版本 */
final class OpenViews {

    private OpenViews() {
    }

    record CreateOrderReq(String outOrderNo, String storeRef, Long amountMinor, String currency, String subject,
                          Integer expireMinutes, String bizNo) {
    }

    record StartReq(String payMethod, String payerOpenId, String payerAppId) {
    }

    record CloseReq(String reason) {
    }

    record RefundReq(String outRefundNo, Long amountMinor, String reason) {
    }

    record OrderView(String payOrderNo, String outOrderNo, String status, long amountMinor, long paidMinor,
                     long refundedMinor, String currency, String market, String payChannel, String payMethod,
                     Long expireAt, Long succeededAt, Long closedAt, String checkoutUrl) {

        static OrderView of(PayOrder o, String checkoutBase) {
            return new OrderView(o.getPayOrderNo(), o.getOutOrderNo(), o.getStatus(), nz(o.getAmountMinor()),
                    nz(o.getPaidMinor()), nz(o.getRefundedMinor()), o.getCurrency(), o.getMarket(), o.getPayChannel(),
                    o.getPayMethod(), epoch(o.getExpireAt()), epoch(o.getSucceededAt()), epoch(o.getClosedAt()),
                    checkoutBase + "/checkout/" + o.getPayOrderNo());
        }
    }

    record RefundView(String payRefundNo, String outRefundNo, String status, long amountMinor, String currency,
                      Long succeededAt) {

        static RefundView of(PayRefund r) {
            return new RefundView(r.getPayRefundNo(), r.getOutRefundNo(), r.getStatus(), nz(r.getAmountMinor()),
                    r.getCurrency(), epoch(r.getSucceededAt()));
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
