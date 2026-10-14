package ai.neargo.shop.spi.trade;

import java.util.List;

/**
 * user → trade：「我买过的商家」（C-MC-01 / `merchants` 页）。
 *
 * <p>方向与 {@code MerchantQueryPort}（trade → user）相反，两个模块因此**互相都不直接依赖对方**，
 * 只各自依赖 `shop-spi`。这不是循环依赖 —— 循环依赖是模块间的，Port 之间的双向调用是正常的协作。
 */
public interface PurchaseHistoryPort {

    /**
     * @return 该用户下过单的商家统计，按最近下单时间倒序
     */
    List<MerchantPurchase> purchasedMerchants(String userNo);

    record MerchantPurchase(String merchantNo, int orderCount, long lastOrderAt) {
    }

    /**
     * 该用户在哪些<b>门店</b>成交过（TDD-C端门店化与门店门户 · 「我的店」）。
     *
     * <p>与 {@link #purchasedMerchants} 的口径<b>有意不同</b>：那一个连取消的单也算（理由是
     * 「取消过也算逛过」）；门店化之后「逛过」有了自己的记录（{@code usr_store_view}），
     * 这里就回到成交口径 {@code OrdSubOrder.PAID} —— 否则「买过 1 次」会挂在一张取消掉的单上。
     *
     * <p>没有门店号的老子单不计：它们下单时还没有门店维度，猜一个门店给它等于编数据。
     *
     * @return 按最近成交时间倒序
     */
    List<StorePurchase> purchasedStores(String userNo);

    record StorePurchase(String storeNo, String merchantNo, int orderCount, long lastOrderAt) {
    }
}
