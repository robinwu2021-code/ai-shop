package ai.neargo.shop.marketing.group.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 商家报价。一个商家对一个需求单只有一条 —— 改价是改它，不是新开一条。 */
@Getter
@Setter
@TableName("mkt_quote")
public class MktQuote extends BaseEntity {

    /** 报价有效。 */
    public static final String ACTIVE = "ACTIVE";
    /** 商家自行撤回。 */
    public static final String WITHDRAWN = "WITHDRAWN";
    /**
     * 平台判定毁约（P-8.2.5）。**同时写一条 mch_violation(type=BREACH)**，计入商家信用。
     *
     * <p>此前这个值不存在：`/ops/quotes/{no}/breach` 在契约里声明着，
     * 而状态列的注释只有 ACTIVE/WITHDRAWN —— 上下游都建好了，中间少一个枚举值。
     * 这种缺口比缺表难发现，它不会在任何「表建了没有」的检查里露头。
     */
    public static final String BREACH = "BREACH";

    private String quoteNo;
    private String requestNo;
    private String entityNo;
    private Long unitPriceMinor;

    /** 起订量。 */
    private Integer minQty;

    private String note;
    private Long validUntil;

    /** 改价次数，>0 时端上展示改价历史入口。 */
    private Integer revisionCount;

    private Boolean chosen;
    private String status;
}
