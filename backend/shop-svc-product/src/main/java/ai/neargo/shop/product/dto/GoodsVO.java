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
                      boolean onSale) {

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
