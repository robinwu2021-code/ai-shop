package ai.neargo.shop.promotion.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 自己组合的一行（原型 s11）：一个条件或一个优惠。条件全部满足才生效，优惠按 seq 依次叠加。
 * 有行时优先于主表那一组触发 × 优惠。
 */
@Getter
@Setter
@TableName("pmt_activity_rule")
public class PmtActivityRule extends BaseEntity {

    public static final String CONDITION = "CONDITION";
    public static final String BENEFIT = "BENEFIT";

    /** 条件：满金额 / 满件数 / 指定商品 */
    public static final String COND_AMOUNT = "AMOUNT";
    public static final String COND_QTY = "QTY";
    public static final String COND_GOODS = "GOODS";
    /** 优惠：减钱 / 打折（带封顶）/ 送积分 */
    public static final String BEN_CUT = "CUT";
    public static final String BEN_PERCENT = "PERCENT";
    public static final String BEN_POINTS = "POINTS";

    private String activityNo;
    private String kind;
    private Integer seq;
    private String ruleType;
    /** JSON：AMOUNT {minor} · QTY {n} · GOODS {goodsNos} · CUT {minor} · PERCENT {bp, capMinor} · POINTS {n} */
    private String params;
}
