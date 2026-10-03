package ai.neargo.shop.product.dto;

import java.util.Map;

/**
 * 平台端的 **SKU 粒度**视图（对齐 ops-web {@code lib/types/product.ts} 的 {@code Sku}）。
 *
 * <p>与 {@link OpsGoodsListVO} 的差别不是「详细一点」，是**粒度不同**：那个一行一件商品
 * （SKU 收在 {@code skus[]} 里），这个一行一个规格。「库存与预售」tab 要看的是
 * 「哪个规格开了预售、卖了多少、什么时候截单」—— 按商品聚合之后这几个数就没有落点了
 * （同一件商品的三个规格完全可以有三套预售配置）。
 *
 * <p>标题、类目、审核状态取自父商品 {@code prd_goods}：那几样本来就挂在商品上，
 * 在这里是**快照**，不是 SKU 自己的字段。
 *
 * @param prices  按市场分别定价（B6）。{@code prd_sku} 一个逻辑 SKU 在库里是「一市场一行」
 *                （唯一键 {@code entity_no,sku_no,market}），这里按 skuNo 分组聚合。
 *                <b>缺某个市场 = 没这个市场的价，不是 0 元</b>
 * @param status  PENDING / ON_SALE / OFF_SALE / REJECTED —— 与 {@code GET /ops/goods} 同一套口径
 *                （库里那列仍叫 AUDITING，词典 §11）。两处不一致会让同一件商品
 *                在两个 tab 里显示成两种状态
 * @param reason  最近一次驳回 / 强制下架的原因。**它是商家能看到的那半边**
 */
public record OpsSkuDetailVO(String skuNo,
                             String goodsNo,
                             TitleVO title,
                             String merchantNo,
                             String merchantName,
                             String categoryNo,
                             String categoryName,
                             String status,
                             Map<String, Long> prices,
                             int stock,
                             /** 预售额度（P-3.3.1）。0 = 不做预售 */
                             int presaleQuota,
                             /** 预售期内已售。> presaleQuota 即超卖（P-3.3.3） */
                             int soldCount,
                             /** 截单时间（P-3.3.2）。null = 不设截单，只靠额度封顶 */
                             String cutoffAt,
                             /** 到货时间。截单必须早于它 */
                             String arriveAt,
                             String createdAt,
                             String reason) {

    /** 三语标题。{@code zh} 是基准，en/ar 缺失时端上按 R9 回落展示 zh。 */
    public record TitleVO(String zh, String en, String ar) {
    }
}
