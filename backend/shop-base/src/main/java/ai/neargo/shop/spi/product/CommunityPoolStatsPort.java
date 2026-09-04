package ai.neargo.shop.spi.product;

import java.util.Map;

/**
 * 社区池的聚合口径。**位置分布那张表的供给侧就是它** ——
 * 「这个聚落有几家商家在卖、几件货」问的不是「谁框了这儿」，
 * 而是展开、上架、履约都过了之后**买家真正搜得到什么**。
 *
 * <p>两者差得可能很远：一个商家框了整个区却一件货都没上，在「他框了什么」里是 1，
 * 在这里是 0 —— 而运营要据此决定去哪儿招商，看错一个就是白跑一趟。
 */
public interface CommunityPoolStatsPort {

    /**
     * 按聚落聚合。<b>只统计池里有的</b> —— 池是派生索引，它为空就是买家搜不到，
     * 不管商家那边配成什么样。
     *
     * @return communityNo → 统计；池里没有的聚落**不在 map 里**（调用方补 0，
     *         这样「没有这一行」与「这一行是 0」在数据层就不会混）
     */
    Map<String, PoolStat> byCommunity();

    /**
     * @param merchantCount 在这个聚落有货的主体数
     * @param goodsCount    在这个聚落搜得到的商品数
     */
    record PoolStat(int merchantCount, int goodsCount) {
    }
}
