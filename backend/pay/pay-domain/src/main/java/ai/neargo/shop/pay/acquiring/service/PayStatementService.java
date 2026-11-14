package ai.neargo.shop.pay.acquiring.service;

import ai.neargo.shop.pay.access.PayCaller;
import java.time.LocalDate;
import java.util.List;

/**
 * 本店收款日流水（F43，I6 前只读支付单与退款单已有数据）。「哪一天」按门店市场的业务日切（{@code BizDates}），
 * 与流水分录的业务日同一口径 —— 两处各算一遍的话，跨日界的那一笔会在这里算昨天、在账上算今天。
 */
public interface PayStatementService {

    DailyStatement daily(PayCaller caller, Long storeId, LocalDate bizDate);

    record DailyStatement(LocalDate bizDate, String currency, int paidCount, long paidMinor, int refundCount,
                          long refundMinor, List<Item> items) {
    }

    /** @param kind PAYMENT / REFUND；@param at UTC 毫秒 */
    record Item(String kind, String no, String payOrderNo, long amountMinor, String payChannel, String payMethod,
                String status, Long at) {
    }
}
