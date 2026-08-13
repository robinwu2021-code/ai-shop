package ai.neargo.shop.product.dto;

/**
 * 常买清单行（C-ST-02）。
 *
 * @param price     **当前**价
 * @param lastPrice 上次成交价 —— 两者不同时端上标「已涨价/已降价」。
 *                  老客对价格敏感，悄悄涨价比涨价本身更伤复购
 * @param invalid   已下架。仍然列出来而不是消失：用户记得自己买过，
 *                  直接不见会让他以为系统丢了数据
 */
public record FrequentItemVO(String goodsNo,
                             String skuNo,
                             String title,
                             String cover,
                             String spec,
                             long price,
                             long lastPrice,
                             /** 买过几次。**列表按它排序，不是按时间** */
                             int times,
                             /** 上次购买时间 */
                             long lastAt,
                             int available,
                             boolean invalid) {
}
