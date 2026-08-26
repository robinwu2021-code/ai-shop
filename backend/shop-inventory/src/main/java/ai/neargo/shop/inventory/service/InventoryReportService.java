package ai.neargo.shop.inventory.service;

import ai.neargo.shop.inventory.dto.InventoryVOs.MonthlyVO;
import ai.neargo.shop.inventory.dto.InventoryVOs.RankVO;

import java.util.List;

/**
 * 报表。**只出货的账**，不出钱的账。
 *
 * <p><b>毛利不在这里</b>：它要 {@code 售价 − 成本}，而售价在销售域 ——
 * 本域的出库单刻意只带成本不带售价（同一件货不同渠道售价不同，
 * 存进来就有了第二个销售真源）。所以本域给的是**销量与销货成本**，
 * 毛利由调用方拿订单金额减去销货成本。
 *
 * <p>D3 之前直接聚合流水；量上来之后再落 {@code inv_daily_snapshot}。
 * 今天的量完全够用，先建快照表反而要维护一份可能与流水不一致的派生数据。
 */
public interface InventoryReportService {

    /**
     * 进销存月报的五个数。
     *
     * <p><b>{@code balanced} 检查的是什么</b>：不是「期初算得对不对」——
     * 期初本身就是 {@code 期末 − 本期净变动} 推出来的，那样自查必然为真。
     * 它查的是**分类有没有漏**：进/销/损/调四组之和是否等于本期净变动。
     * 新加了一个 {@code reasonCode} 而没归到任何一组时，这一条会变红 ——
     * 而那正是「报表少了一块，但每个数看着都对」的那种错。
     */
    MonthlyVO monthly(String ownerId, String locationId, String month);

    /** @param type {@code fast} 动销 / {@code slow} 滞销 */
    List<RankVO> ranking(String ownerId, String locationId, String type, int days, int limit);

    /**
     * 导出 CSV。**UTF-8 带 BOM** —— 不带的话 Excel 打开中文全是乱码，
     * 而这件事 100% 会发生。
     *
     * @param type {@code ledger} 流水 / {@code balances} 结存
     */
    String exportCsv(String ownerId, String locationId, String type);
}
