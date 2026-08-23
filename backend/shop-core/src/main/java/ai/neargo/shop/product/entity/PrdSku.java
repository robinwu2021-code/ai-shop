package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * SKU 与价格。**价格的唯一权威**（TDD-backend §6.3 / R17 / B11）。
 *
 * <p>唯一键是 {@code (entity_no, sku_no, market)}：
 * <ul>
 *   <li>带 {@code entity_no}：同一件货不同商家可以不同价，这是撮合平台的常态</li>
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
    private String entityNo;

    /** 市场：CN / AE / … 一期恒 CN。 */
    private String market;

    /** JSON 数组，各规格维度上的取值，顺序与 {@code PrdGoods.specGroups} 一一对应。 */
    private String optionValues;

    /**
     * JSON 数组：与 {@link #optionValues} 一一对应的<b>规格值编号</b>（{@code prd_spec_value.value_no}）。
     *
     * <p><b>是快照，不是外键。</b>值改名（黑色 → 曜石黑）不能改写历史订单里那件货当时的样子，
     * 所以文案照旧留在 {@code optionValues}；这一列只用于聚合与比价 ——
     * 三家店的「500g」只有都指向 {@code SV_WEIGHT_W500G} 才比得起来。
     *
     * <p>商家手打、没落到任何值上的那一格是 {@code null}：于是「有多少规格还没归一」
     * 第一次变成可查的，而不是像此前那样查都查不出来（线上 378 件商品，带 optionCode 的 0 件）。
     */
    private String optionValueNos;

    /** 展示用拼接文案，后端下发 —— 端上自己拼会在多语言分隔符上出岔子。 */
    private String spec;

    /** 最小货币单位（分）。整数，绝不用浮点。 */
    private Long price;
    private Long originPrice;

    /**
     * 成本价（最小货币单位）。{@code null} = 商家没填。
     *
     * <p><b>只在商家侧下发</b>（{@code /biz/goods/**}），买家端与运营端都拿不到 ——
     * 进货价是商家的经营秘密，平台没有理由把它转发给别人。
     *
     * <p>不校验「必须低于售价」：引流款本来就可能亏本卖，拦住它等于替商家做生意。
     */
    private Long costPrice;

    private Integer stock;

    /** 已锁定未支付的数量。可售 = stock - locked。 */
    private Integer lockedStock;

    /** FRESH 且按重计价：标称重量（克）。 */
    private Integer nominalGram;

    /**
     * 预售额度（V100 / P-3.3.1）。**0 = 不做预售**，下单闸门只看现货。
     *
     * <p>它不是一个给人看的配置项 —— 现货卖完后，下单会回落到这个额度上继续成交
     * （{@code StockPortImpl.lock()} 的第二级）。只存不读的话，配 500 和配 0
     * 对买家完全一样，而运营会以为自己开了预售。
     */
    private Integer presaleQuota;

    /** 预售期内已售。锁定即计入、释放即回退 —— 与 {@link #lockedStock} 是两个池子。 */
    private Integer soldCount;

    /**
     * 截单时间（P-3.3.2）。{@code null} = 不设截单，只靠额度封顶。
     * <b>必须早于 {@link #arriveAt}</b>：否则货到了还能继续下单，而那批订单没有对应的采购。
     */
    /**
 * <b>预售截单</b>：这批预售额度什么时候停止收单。<b>只有运营能设</b>
 * （{@code POST /ops/skus/{no}/presale}），是 {@code lockPresale} 的 WHERE 条件之一 ——
 * 少了它，次日现采的采购单已经下了还在继续进新订单。
 *
 * <p>⚠️ 与 {@code prd_goods.cutoff_at}（BIGINT，商家填的生鲜日常截单）
 * <b>同名不同物</b>，责任人也不同。见 {@code PrdGoods#cutoffAt} 的说明。
 */
    private java.time.LocalDateTime cutoffAt;

    /** 到货时间。一期只做「截单必须早于到货」的校验基准，不驱动履约批次。 */
    private java.time.LocalDateTime arriveAt;
}
