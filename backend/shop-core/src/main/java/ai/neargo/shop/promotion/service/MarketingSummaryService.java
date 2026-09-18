package ai.neargo.shop.promotion.service;

/**
 * B 端营销入口（原型 s01）：一屏的数字，每个数字回答一个不同的问题。
 *
 * <p>全部现算，不存计数 —— 存一份就会在某一天和点进去看到的对不上。
 */
public interface MarketingSummaryService {

    SummaryVO summary(String entityNo);

    /**
     * @param monthDiscountMinor 本月让利（活动 + 券，已撤销的不算）
     * @param monthOrders        本月享受过优惠的订单数
     * @param activityRunning    进行中的活动数
     * @param couponIssuing      在发的券数
     * @param periodTodayQty     今天收单中的各期已订份数之和（已付款且未退）
     * @param periodTodayCutoffAt 今天最早的截单时刻；今天没有收单中的期时为空
     * @param periodsShort       未达起订量、等商家处理的期数（黄标）
     * @param groupsShort        还差人的团数（黄标）
     * @param quotesPending      等待报价的求团需求数（黄标）
     * @param enrollable         可报名的平台活动数（P3 之前恒为 0）
     */
    record SummaryVO(long monthDiscountMinor, int monthOrders,
                     int activityRunning, int couponIssuing,
                     int periodTodayQty, Long periodTodayCutoffAt, int periodsShort,
                     int groupsShort, int quotesPending, int enrollable) {

        /**
         * 团那两个数由门户层补上：团在 marketing 域，promotion 不直接依赖它
         *（ArchitectureTest 的域间规则）。一屏数字分两处取，拼在唯一的出口上。
         */
        public SummaryVO withGroups(int groupsShort, int quotesPending) {
            return new SummaryVO(monthDiscountMinor, monthOrders, activityRunning, couponIssuing,
                    periodTodayQty, periodTodayCutoffAt, periodsShort, groupsShort, quotesPending, enrollable);
        }
    }
}
