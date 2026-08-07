package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * SKU 与价格。**价格的唯一权威**（TDD-backend §6.3 / R17 / B11）。
 *
 * <p>唯一键是 {@code (merchant_no, sku_no, market)}：
 * <ul>
 *   <li>带 {@code merchant_no}：同一件货不同商家可以不同价，这是撮合平台的常态</li>
 *   <li>带 {@code market}：多市场必须分别定价，不做汇率换算（B6）—— 汇率换算出来的
 *       价格会随汇率跳动，商家无法接受</li>
 * </ul>
 *
 * <p>社区商品池（{@code prd_community_pool}）只决定<b>可见性</b>，不存价。
 * 两处各存一份价就得靠同步任务保证一致，而同步任务必然有窗口期。
 */
@Getter
@Setter
@TableName("prd_sku")
public class PrdSku extends BaseEntity {

    private String skuNo;
    private String goodsNo;
    private String merchantNo;

    /** 市场：CN / AE / … 一期恒 CN。 */
    private String market;

    /** JSON 数组，各规格维度上的取值，顺序与 {@code PrdGoods.specGroups} 一一对应。 */
    private String optionValues;

    /** 展示用拼接文案，后端下发 —— 端上自己拼会在多语言分隔符上出岔子。 */
    private String spec;

    /** 最小货币单位（分）。整数，绝不用浮点。 */
    private Long price;
    private Long originPrice;

    private Integer stock;

    /** 已锁定未支付的数量。可售 = stock - locked。 */
    private Integer lockedStock;

    /** FRESH 且按重计价：标称重量（克）。 */
    private Integer nominalGram;
}
