package ai.neargo.shop.trade.dto;

/**
 * 购物车行（对齐 c-app {@code CartItem}）。
 *
 * <p>价格与失效标记都是**读的时候实时算的**，不是加购时的快照 ——
 * 加购到结算之间商品可能调价或下架，端上的失效区与涨价提示依赖这两个字段。
 */
public record CartItemVO(String goodsNo,
                         String skuNo,
                         String title,
                         String cover,
                         String spec,
                         long price,
                         int qty,
                         String type,
                         String fulfillment,
                         String merchantNo,
                         String merchantName,
                         boolean selected,
                         /** 失效（下架/删除）：端上放进失效区，不参与结算 */
                         boolean invalid,
                         /** 可售库存，0 表示售罄 */
                         int available) {
}
