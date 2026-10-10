package ai.neargo.shop.spi.trade;

import java.util.Collection;
import java.util.List;

/**
 * promotion → trade：<b>社区集单</b>一期里有哪些单、以及取消一期时把钱退回去。
 *
 * <p>期上<b>不存份数与金额</b>，从这里现算（与 {@code FulfillmentStatsPort} 同一条原则）。
 *
 * <p>退款走售后的系统全额退款（{@code AfterSaleService#systemRefund}），
 * 与商家同意、平台裁决同一条收尾路径 —— <b>不是只改状态</b>
 *（{@code abortGroup} 那样做的结果是钱一分没退）。
 */
public interface PeriodOrderPort {

    /** 这几期的全部订单行（含未支付、已取消的，由调用方按 {@link PeriodLine#subStatus} 取舍） */
    List<PeriodLine> lines(Collection<String> periodNos);

    /**
     * 把这一期里已付款、尚未退款的子单逐张全额退掉。
     *
     * <p><b>逐张独立</b>：一张退失败（分账回退失败停在 REFUNDING 等重试）不影响其余；
     * 未支付的子单不在这里处理 —— 它们会被超时关单关掉，没有钱可退。
     * 幂等：已退款或已有退款在路上的子单直接跳过，重复调用不会退两次。
     *
     * @return 这一次新发起退款的子单数
     */
    int refundAll(String periodNo, String reason);

    /** 子单状态常量与 trade 同源；这里只列调用方要判的几个 */
    String WAIT_PAY = "WAIT_PAY";
    String CANCELLED = "CANCELLED";
    String REFUNDED = "REFUNDED";

    /**
     * @param subStatus  子单状态（WAIT_PAY / WAIT_FULFILL / FULFILLING / COMPLETED / CANCELLED / REFUNDED）
     * @param amountMinor 这一行的金额（分）
     */
    record PeriodLine(String periodNo, String subOrderNo, String userNo, String subStatus,
                      String pickupNo, String pickupName,
                      String goodsNo, String skuNo, String title, String spec,
                      int qty, long amountMinor) {

        /** 已付款且没退：计入份数、汇总、起订量 */
        public boolean paidAndKept() {
            return !WAIT_PAY.equals(subStatus) && !CANCELLED.equals(subStatus) && !REFUNDED.equals(subStatus);
        }

        /** 占着名额：已付款的，加上还在等付款的（不算进去会超卖） */
        public boolean holdsQuota() {
            return !CANCELLED.equals(subStatus) && !REFUNDED.equals(subStatus);
        }
    }
}
