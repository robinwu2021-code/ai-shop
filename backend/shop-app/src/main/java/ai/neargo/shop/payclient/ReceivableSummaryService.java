package ai.neargo.shop.payclient;

import java.util.List;

/**
 * 业务单收款汇总（TDD-业务单收款汇总，执行方案 D5）：同一业务单号下付款链接、外部记账、储值扣款加起来算「已收 / 还差」。
 *
 * <p>只读：应收由调用方给（App 从结算单 / 工单账单读出），这里不落库、不改任何单据状态。
 * 静态收款码收的钱没有业务单号，不在其内（D14）。
 */
public interface ReceivableSummaryService {

    /**
     * @param dueMinor     应收（最小单位，≥ 0）
     * @param currency     应收币种；币种不同的收款不计入，列进 {@code anomalies}
     * @param relatedBizNo 关联单号（可空）：记在它名下的收款一并计入，典型是美业预约号下的订金在工单结账时抵扣
     *                     （执行方案 D6 / W1.4）；不能与 {@code bizNo} 相同
     */
    Summary summarize(String entityNo, String storeNo, String actorNo, String bizNo, String relatedBizNo,
                      long dueMinor, String currency);

    /**
     * @param outstandingMinor {@code max(0, due - received)}
     * @param overpaidMinor    {@code max(0, received - due)}
     */
    record Summary(String bizNo, String currency, long dueMinor, long receivedMinor, long outstandingMinor,
                   long overpaidMinor, List<Line> lines, List<Line> anomalies) {
    }

    /**
     * @param bizNo        这一行记在哪张单名下（本单或关联单）
     * @param kind         PAY_LINK / OFFLINE / STORED_VALUE
     * @param refNo        支付单号 / 外部记账记录号 / 储值消费号
     * @param status       各自来源的状态原样（付款链接 PENDING / PAID / EXPIRED / VOIDED；外部记账 RECORDED / VOIDED；
     *                     储值 COMMITTED / REVERSED …）
     * @param countedMinor 计入已收的金额；付款链接为 {@code paid - refunded}
     * @param at           创建或实收时刻（epoch 毫秒）
     */
    record Line(String bizNo, String kind, String refNo, String status, String currency, long amountMinor, long countedMinor,
                Long at) {
    }
}
