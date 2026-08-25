package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 类目 × 支付方式：支付方式四层判定的<b>第 ① 层</b>。
 *
 * <p><b>没有行 = 放行</b>，不是「没有行 = 禁止」。
 * 一期只想用「主体资质」那一层做主力，其余三层默认放行；
 * 设计成白名单的话，上线当天就得先把 57 个类目全配一遍才有人下得了单。
 * 要禁某个类目时才插一行 {@code allowed=0}。
 *
 * <p><b>与积分的合成规则相反，别混</b>：
 * 积分发多少是「取一个值」（类目 → 兜底，命中即停），因为它是数值规则；
 * 支付方式能不能用是「取交集」（四层全放行才可用），因为它是能力与许可。
 */
@Getter
@Setter
@TableName("prd_category_pay_mode")
public class PrdCategoryPayMode extends BaseEntity {

    private String categoryNo;

    /** {@link ai.neargo.shop.common.PayModes} 的取值域。 */
    private String payMode;

    /** 0 = 这个类目下禁用该支付方式。**只有显式插了 0 才是禁止**。 */
    private Integer allowed;
}
