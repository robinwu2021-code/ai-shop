package ai.neargo.shop.product.dto;

import java.util.List;

/**
 * 门店主页（C-ST-01）。**游客可访问，不经首页与选社区** —— 扫码/分享直达。
 *
 * <p>{@code hotGoods} 只给前几个：店主的货本来就不多，主页不做分页，
 * 真要找东西走店内搜索。
 */
public record StoreHomeVO(Merchant merchant,
                          String notice,
                          String fulfillmentDesc,
                          boolean favorited,
                          List<GoodsVO> hotGoods) {

    /** 门店主页只需要商家的展示信息，不需要完整详情（那是商家详情页的事）。 */
    public record Merchant(String merchantNo, String name, String logo,
                           double rating, boolean verified, int breachCount) {
    }
}
