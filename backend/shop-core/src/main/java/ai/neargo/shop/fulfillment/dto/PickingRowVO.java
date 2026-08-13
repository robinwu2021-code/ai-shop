package ai.neargo.shop.fulfillment.dto;

import java.util.List;

/**
 * 分拣单行（B-10.3.1 按商品视图）。
 *
 * <p>按商品聚合而不是按订单列：到货当日店主要做的是「把 30 袋米分成 12 堆」，
 * 按订单列会让他在 12 条记录之间反复找同一个商品。
 */
public record PickingRowVO(String goodsNo,
                           /** 分拣按 SKU 汇总 —— 两个规格要分两堆点 */
                           String skuNo,
                           String title,
                           /** 认货用的图 */
                           String cover,
                           String spec,
                           int totalQty,
                           int buyerCount,
                           /**
                            * 谁要几件。
                            *
                            * <p>此前只给 {@code buyerCount}，而分拣单上真正要做的事是
                            * <b>照着名字分堆</b>：数字只能告诉店主「有 3 个人」，
                            * 分不出哪几件是谁的。端上的契约一直是这份明细
                            * （页面直接 `for (const b of r.buyers)`），后端不发的话
                            * 那一行在真机上直接抛错 —— 只是分拣单常常是空的，没人撞上。
                            */
                           List<Buyer> buyers) {

    /** @param orderNo 子单号：点某个人可以上报短少/破损，要能定位到那一单 */
    public record Buyer(String nickname, int qty, String orderNo) {
    }
}
