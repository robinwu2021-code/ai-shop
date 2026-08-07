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
}
