package ai.neargo.shop.pay.terminal.service;

import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.acquiring.entity.PayOrder;

/**
 * 门店收款编排（pay-guard 开发计划 M6.4；代码清单 §4.2 {@code InStorePaymentService}）：终端建单 → 刷卡 / 扫顾客付款码 →
 * 结果不明记 {@code UNKNOWN} → 终端轮询查单补账 → 关前必查单。
 *
 * <p><b>同步扣款结果不明时绝不判失败</b>：卡已经刷了、渠道还没回，判失败就是「顾客被扣了钱、店里说没付」。
 * 返回 {@code UNKNOWN}，终端必须调 {@link #payment} 查到结论为止。
 *
 * <p><b>沙箱</b>：刷卡真渠道要等读卡套件规格（代码清单 Q12），今天刷卡与扫码都经 {@code shop.pay.terminal.sandbox-pay-method}
 * 指定的方式（联调用 {@code TEST_PAY}）发起；没配就拒 —— 生产不配，生产环境刷卡因此不会误走测试通道。加密卡数据原样透传、不落日志。
 */
public interface InStorePaymentService {

    /** @param status {@code SUCCEEDED} / {@code UNKNOWN}（结果不明，必须查单）/ {@code CLOSED} / 支付单的其它状态 */
    record Charge(String payOrderNo, String status, long amountMinor, String currency) {

        public static Charge of(PayOrder o) {
            String s = o.getStatus();
            String view = "SUCCEEDED".equals(s) || "PARTIALLY_REFUNDED".equals(s) || "REFUNDED".equals(s) || "CLOSED".equals(s)
                    ? s : "AWAITING_PAYMENT".equals(s) ? "UNKNOWN" : s;
            return new Charge(o.getPayOrderNo(), view, o.getAmountMinor() == null ? 0 : o.getAmountMinor(), o.getCurrency());
        }
    }

    /** 终端在本店建一张门店收款单（门店取终端绑定的那一家，不接受入参） */
    Charge create(PayCaller caller, String outOrderNo, long amountMinor, String currency, String subject);

    /** 刷卡：{@code encryptedCardData} 是读卡套件的密文，不解析、不落日志 */
    Charge chargeCard(PayCaller caller, String payOrderNo, String encryptedCardData);

    /** 扫顾客付款码 */
    Charge chargeCustomerCode(PayCaller caller, String payOrderNo, String authCode);

    /** 轮询：结果不明时终端必须先调它（查单补账，不关单） */
    Charge payment(PayCaller caller, String payOrderNo);

    /** 取消：关前必查单 —— 查到已付就不关，返回已付（「终端已扣、我方已关」两边都挡） */
    Charge cancel(PayCaller caller, String payOrderNo);
}
