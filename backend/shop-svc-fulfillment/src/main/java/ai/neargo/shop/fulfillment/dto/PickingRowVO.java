package ai.neargo.shop.fulfillment.dto;

/**
 * 分拣单行（B-10.3.1 按商品视图）。
 *
 * <p>按商品聚合而不是按订单列：到货当日店主要做的是「把 30 袋米分成 12 堆」，
 * 按订单列会让他在 12 条记录之间反复找同一个商品。
 */
public record PickingRowVO(String goodsNo,
                           String title,
                           String spec,
                           int totalQty,
                           int buyerCount) {
}
