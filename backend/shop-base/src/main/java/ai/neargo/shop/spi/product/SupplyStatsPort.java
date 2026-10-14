package ai.neargo.shop.spi.product;

import java.util.Map;

/**
 * 每个小区的<b>供给</b>：有几家商家在卖、几件货。位置分布那张表的供给侧就是它 ——
 * 问的不是「谁框了这儿」，而是展开、上架、门店状态都过了之后<b>买家真正搜得到什么</b>。
 *
 * <p>两者差得可能很远：一个商家框了整个区却一件货都没上，在「他框了什么」里是 1，
 * 在这里是 0 —— 而运营要据此决定去哪儿招商，看错一个就是白跑一趟。
 *
 * <p>查询时现算，与买家列表同一个判定（方案-商品可见性改查询时关联 F9）。
 */
public interface SupplyStatsPort {

    /**
     * 按小区聚合。
     *
     * @return communityNo → 统计；没有供给的小区<b>不在 map 里</b>（调用方补 0，
     *         这样「没有这一行」与「这一行是 0」在数据层就不会混）
     */
    Map<String, SupplyStat> byCommunity();

    /**
     * @param merchantCount 在这个小区有货的主体数
     * @param goodsCount    在这个小区搜得到的商品数
     */
    record SupplyStat(int merchantCount, int goodsCount) {
    }
}
