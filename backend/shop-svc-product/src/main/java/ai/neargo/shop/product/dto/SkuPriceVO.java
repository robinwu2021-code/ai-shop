package ai.neargo.shop.product.dto;

/**
 * 规格选中后的实时价格（C-PD-04）。
 *
 * <p>为什么不复用详情里的 SKU 数据：详情可能被端上缓存几分钟，而用户点规格到下单之间
 * 商家可能调价或卖空。这个端点是**下单前的最后一次校准**。
 */
public record SkuPriceVO(String skuNo,
                         String spec,
                         long price,
                         Long originPrice,
                         int stock) {
}
