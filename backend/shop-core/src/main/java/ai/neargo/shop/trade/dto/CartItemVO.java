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
                         int available,
                         /**
                          * 失效原因**码**（不是文案）：{@code OFF_SHELF / ACTIVITY_ENDED / SOLD_OUT}，
                          * 可售时为 {@code null}。端上按码映射到 i18n 词条 ——
                          * <b>绝不在这里发中文</b>：那是要显示给用户的话，而 app 有三门语言，
                          * 后端发文案会绕过 i18n 守卫（见 CartItem 注释里那段旧坑）。
                          * 与 {@code invalid}/{@code available} 不冲突：那两个是粗事实，这是更细的一层。
                          */
                         String invalidReason,
                         /** 所属门店（ADR-031）：购物车与结算按门店分段，段头显示店名。老数据为空 */
                         String storeNo,
                         String storeName) {

    public CartItemVO(String goodsNo, String skuNo, String title, String cover, String spec, long price, int qty,
                      String type, String fulfillment, String merchantNo, String merchantName, boolean selected,
                      boolean invalid, int available, String invalidReason) {
        this(goodsNo, skuNo, title, cover, spec, price, qty, type, fulfillment, merchantNo, merchantName,
                selected, invalid, available, invalidReason, null, null);
    }

    public static final String REASON_OFF_SHELF = "OFF_SHELF";
    public static final String REASON_ACTIVITY_ENDED = "ACTIVITY_ENDED";
    public static final String REASON_SOLD_OUT = "SOLD_OUT";
}
