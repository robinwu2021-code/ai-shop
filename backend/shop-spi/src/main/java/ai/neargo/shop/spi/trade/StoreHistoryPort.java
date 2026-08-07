package ai.neargo.shop.spi.trade;

import java.util.List;

/**
 * store → trade：「我在这家店买过什么」（C-ST-02 常买清单）。
 *
 * <p>只统计**已支付**的单：加购未付、下单未付都不算「买过」——
 * 常买清单是复购入口，混进没成交的记录会让第一屏出现用户其实没买过的东西。
 */
public interface StoreHistoryPort {

    List<PurchasedSku> purchasedSkus(String userNo, String merchantNo);

    /**
     * @param lastPrice 上次成交价。与当前价不同时端上标「已涨价/已降价」——
     *                  老客对价格敏感，悄悄涨价比涨价本身更伤复购
     */
    record PurchasedSku(String goodsNo, String skuNo, String title, String spec,
                        long lastPrice, int buyCount, long lastBoughtAt) {
    }
}
