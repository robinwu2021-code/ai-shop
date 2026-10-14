package ai.neargo.shop.spi.trade;

import java.util.Optional;

/**
 * product → trade：发表评价前要核实「这一单确实买了、确实完成了」。
 *
 * <p>为什么要走 Port 而不是让 review 直接查订单表：`shop-svc-product` 依赖 `shop-svc-trade`
 * 会被 {@code ArchitectureTest} 挡下 —— 模块边界靠自觉维护，三个月后就会拆不动。
 *
 * <p>返回的是**评价所需的最小事实**，不是整张订单：评价只需要知道
 * 「这单属于谁、买的哪个商家的哪个商品、什么规格、完成了没有」。
 * 把整个 Order 传过来会让 product 域依赖 trade 的 DTO 形状，等于绕过边界。
 */
public interface ReviewableOrderPort {

    /**
     * 查订单里某个商品的可评价事实。
     *
     * @param orderNo 订单号（C 端拿到的是主单号）
     * @param goodsNo 被评价的商品 —— 一单多商品时逐个评
     * @return 找不到该订单、或订单里没有这个商品时为空
     */
    Optional<ReviewableItem> findItem(String orderNo, String goodsNo);

    /**
     * @param subOrderNo 评价的唯一键落在 (sub_order_no, goods_no) 上 ——
     *                   一个主单可能拆成多个商家的子单，评价是**对商家**的
     * @param storeNo    **下单那一刻**子单上的门店（ADR-011 P2 固化的那一列）。
     *                   评价归它，不归「商家现在的默认店」—— 顾客评的是当时那家店给他的体验，
     *                   半年后商家把那家店关了，这条评价不该跟着搬家。
     *                   老单可能为空，那样的评价只计主体分（TDD-评价归门店 §2.1）
     * @param completed  是否已完成。未完成不允许评价（验收清单：「订单完成后才能评价」）
     */
    record ReviewableItem(String subOrderNo, String merchantNo, String storeNo, String userNo,
                          String skuNo, String spec, boolean completed) {
    }
}
