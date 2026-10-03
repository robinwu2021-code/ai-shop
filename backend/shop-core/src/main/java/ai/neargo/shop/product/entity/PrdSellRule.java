package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 门店线上可售规则（V346，TDD-商品纳入进销存开关 §4 / §18）。稀疏：没有行 = 全部可售。
 * 取值顺序 单品 › 品类 › 本店默认。行只改不删 —— 唯一键不含 deleted。
 */
@Getter
@Setter
@TableName("prd_sell_rule")
public class PrdSellRule extends BaseEntity {

    public static final String SCOPE_STORE = "STORE";
    public static final String SCOPE_CATEGORY = "CATEGORY";
    public static final String SCOPE_GOODS = "GOODS";

    /** 全部可售：T = 可用 */
    public static final String ALL = "ALL";
    /** 保留线下 N 件：T = max(0, 可用 − N) */
    public static final String RESERVE = "RESERVE";
    /** 按比例 P%：T = ⌊可用 × P%⌋ */
    public static final String RATIO = "RATIO";
    /** 封顶 M 件：T = min(可用, M) */
    public static final String CAP = "CAP";
    /** 手动：店主给的额度，只降不升（可卖超过可用时压到可用） */
    public static final String MANUAL = "MANUAL";
    /**
     * 回到上一级（类目 / 商品专用）。唯一键不含 deleted、行只改不删，所以「撤掉这条覆盖」存成这一档，取值时跳过
     */
    public static final String INHERIT = "INHERIT";

    private String storeNo;
    private String scopeType;
    private String scopeRef;
    private String ruleType;
    private Integer param;
}
