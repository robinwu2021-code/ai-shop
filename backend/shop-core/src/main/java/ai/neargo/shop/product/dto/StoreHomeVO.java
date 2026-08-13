package ai.neargo.shop.product.dto;

import java.util.List;

/**
 * 门店主页（C-ST-01）。**游客可访问，不经首页与选社区** —— 扫码/分享直达。
 *
 * <p>{@code hotGoods} 只给前几个：店主的货本来就不多，主页不做分页，
 * 真要找东西走店内搜索。
 */
public record StoreHomeVO(Merchant merchant,
                          /**
                           * 店主自己维护的门面：公告、营业时间、地址。
                           *
                           * <p>此前这里是一个写死成空串的 {@code notice} 和一句写死的
                           * 「每晚 7 点前到货，凭取货码到店自提」——
                           * 店主在 B 端认真填的公告与营业时间<b>一个字都到不了 C 端</b>。
                           * 而契约要的字段名是 {@code store}，页面读 {@code store.announcement}
                           * 直接抛错：<b>门店主页整页空白</b>，而它是这一版的主获客路径（ADR-004）。
                           */
                          StoreFront store,
                          boolean favorited,
                          /** 在售商品。字段名按契约叫 goods —— 端上读的就是这个名字 */
                          List<GoodsVO> goods) {

    /** 门面文案。三个字段都不会是 null：端上直接渲染，null 会变成屏幕上的「null」 */
    public record StoreFront(String announcement, String openHours, String address) {
    }

    /** 门店主页只需要商家的展示信息，不需要完整详情（那是商家详情页的事）。 */
    public record Merchant(String merchantNo, String name, String logo,
                           double rating, int ratingCount,
                           boolean verified, int breachCount) {
    }
}
