package ai.neargo.shop.pay.acquiring.backfill;

/**
 * 回填 B6 / B7（开发计划 §5 I3；TDD-支付服务-I3支付单退款流水 S9）：I3 之前的收款尝试没有支付单。
 * 按订单号把尝试归到一张支付单下（状态、实收、已退按尝试推出），并按支付单冻结的资金路径回放流水期初。
 *
 * <p>支付域读不到交易表：订单属于商城还是餐饮、门店是哪家，由调用方（shop-app 的回填任务）查好传进来。幂等：已挂支付单的尝试不再动。
 */
public interface PayOrderBackfill {

    /**
     * @param bizType    MALL_ORDER / FOOD_CHECK
     * @param storeId    {@code mch_store.id}
     * @param fundsPath  主体资金路径（DIRECT / AGGREGATED），冻结进支付单、流水期初按它分录
     * @return 新建或已有的支付单 id；这个订单号下没有待回填的尝试为空
     */
    Long backfill(String orderNo, String bizType, Long storeId, String storeName, String entityName, String market,
                  String fundsPath);

    /** 还没挂支付单的收款尝试所属订单号（一批）。回填任务按它分页 */
    java.util.List<String> pendingOrderNos(int limit);
}
