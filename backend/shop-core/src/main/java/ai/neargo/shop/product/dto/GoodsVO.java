package ai.neargo.shop.product.dto;

import java.util.List;

/**
 * 商品（对齐 c-app {@code Goods}）。字段名与顺序都按端上契约来。
 *
 * <p>{@code merchant} 内嵌而不是只给 {@code merchantNo}：商品卡上要显示商家名与认证标，
 * 端上再去批量查商家会让首页多一次串行请求。
 *
 * @param price 展示价 = 最低 SKU 价（端上「¥x 起」）
 */
public record GoodsVO(String goodsNo,
                      String title,
                      String subtitle,
                      String cover,
                      List<String> images,
                      String type,
                      String categoryNo,
                      MerchantBriefVO merchant,
                      double rating,
                      int ratingCount,
                      long price,
                      Long originPrice,
                      List<String> fulfillments,
                      List<SpecGroupVO> specGroups,
                      List<SkuVO> skus,
                      int sales,
                      Long cutoffAt,
                      String arrivalDesc,
                      Boolean weighed,
                      String origin,
                      Integer durationMin,
                      String storeName,
                      int limitPerUser,
                      boolean onSale,
                      /**
                       * 商家侧状态：ON_SALE / OFF_SALE / AUDITING / REJECTED。
                       *
                       * <p><b>只在 B 端下发，C 端恒为 null</b> —— 买家不需要知道
                       * 某件商品是"审核中"还是"被驳回"，那是店主和平台之间的事。
                       *
                       * <p>它不能由 {@code onSale} 推出来：下架的商品与审核中的商品
                       * {@code onSale} 都是 false，而店主对这两者要做的动作完全不同
                       * （一个是点上架，一个是等/改）。
                       */
                      String status) {

    public record MerchantBriefVO(String merchantNo, String name, String logo,
                                  double rating, boolean verified, int breachCount) {
    }

    public record SpecGroupVO(String name, List<String> options) {
    }

    public record SkuVO(String skuNo,
                        List<String> optionValues,
                        String spec,
                        long price,
                        Long originPrice,
                        int stock,
                        Integer nominalGram) {
    }
}
