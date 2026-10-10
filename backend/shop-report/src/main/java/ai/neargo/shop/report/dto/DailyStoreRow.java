package ai.neargo.shop.report.dto;

import java.time.LocalDate;

/**
 * 门店日汇总的一行，与 {@code rpt_daily_store} 一一对应。
 *
 * <p><b>金额一律最小货币单位（分）</b>，字段名带 {@code Minor} 是判据不是习惯 ——
 * 少一个后缀下一个人就会当成元。
 *
 * <p><b>自带客流占比 = {@code ownedOrders / attributedOrders}</b>，分母不是 {@code orders} ——
 * 口径见 {@code MerchantOrderService.StatsSummary} 的 javadoc（早于归因上线的历史单
 * 不该把商家的比例冲低），而这个比例决定费率档。
 *
 * <p><b>没有运费成本</b>：它只在结算域按日算，子单上没有 —— 补它属于 P3。
 *
 * <p><b>平台客流 = {@code orders - ownedOrders}</b>，不单独落列：
 * {@code TrafficSource} 只有 MERCHANT_OWNED / PLATFORM 两个值。
 */
public record DailyStoreRow(
        LocalDate statDate,
        String entityNo,
        String storeNo,
        int orders,
        long gmvMinor,
        int refundOrders,
        long refundMinor,
        int buyers,
        int newBuyers,
        int ownedOrders,
        long ownedGmvMinor,
        int attributedOrders,
        long commissionMinor,
        long serviceFeeMinor,
        long freightIncomeMinor,
        long netMinor,
        String currency) {
}
