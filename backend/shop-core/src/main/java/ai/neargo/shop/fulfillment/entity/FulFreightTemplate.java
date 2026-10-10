package ai.neargo.shop.fulfillment.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 平台运费模板与超区规则（P-5.2.3）。
 *
 * <p><b>单位口径：重量一律克、金额一律分，都是整数。</b>
 * 用小数的代价是 0.1kg + 0.2kg 这类浮点误差在算钱的地方冒出来，而那是对不出账的。
 *
 * <p>⚠️ <b>一期只存不算</b>：下单算价今天读的是商家侧 {@code store_delivery_rule}（V7）。
 * 平台模板接进算价是二期 —— 这是**已知的「存了暂时没人读」**，
 * 记在 TDD-运营端履约调度 §五 T3，不藏着。
 */
@Getter
@Setter
@TableName("ful_freight_template")
public class FulFreightTemplate extends BaseEntity {

    public static final String ACTION_REJECT = "REJECT";
    public static final String ACTION_SURCHARGE = "SURCHARGE";

    private String templateNo;
    private String name;

    /** 首重（克）。下限 100 —— 首重 0 克意味着「拿起来就收首重费」，那是配置错误不是策略。 */
    private Integer firstWeightGram;
    private Long firstFee;

    /** 续重单位（克）。必须 &gt; 0，否则续重费无从计算。 */
    private Integer addWeightGram;
    private Long addFee;

    /** 满多少分免邮；0 = 不免邮。 */
    private Long freeThreshold;

    /** 默认模板<b>不能归档</b> —— 归档之后新商家没有模板可用。 */
    private Integer isDefault;

    /**
     * JSON 数组：{@code [{region,action,surcharge}]}。
     *
     * <p>用 JSON 而不是子表：一个模板的超区条目永远整体读整体写，没有单独按区域查的场景。
     * 开子表换来一次 join 和一份「删模板要不要级联」的心智负担，而换不到任何查询能力。
     */
    private String outOfRange;

    /**
     * 归档时间戳（G1 软删除）。
     *
     * <p>硬删会把历史订单的运费依据一起抹掉 —— 之后谁也说不清那单当时为什么收了 8 元。
     */
    private Long archivedAt;
}
