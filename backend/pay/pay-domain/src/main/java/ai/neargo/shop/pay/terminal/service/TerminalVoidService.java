package ai.neargo.shop.pay.terminal.service;

import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.acquiring.entity.PayRefund;

/**
 * 终端撤销 / 退款（pay-guard 开发计划 M6.4；代码清单 §4.2 {@code TerminalVoidService}）：先验店长授权码，再看单终端日退款上限，
 * 然后走与其它入口同一条退款单流程（可退余额、复核规则、执行器）—— 撤销在沙箱里按当日退款处理（刷卡渠道的撤销接口随 Q12 接入）。
 * 店长记在退款单的 {@code authorized_by}，收银员记 {@code requested_by}。
 */
public interface TerminalVoidService {

    /** @param kind {@code VOID}（当日撤销）/ {@code REFUND}（隔日退款） */
    PayRefund refund(PayCaller caller, String kind, String payOrderNo, long amountMinor, String managerCode,
                            String reason, String outRefundNo);
}
