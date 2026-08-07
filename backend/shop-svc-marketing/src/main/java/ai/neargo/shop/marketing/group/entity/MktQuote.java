package ai.neargo.shop.marketing.group.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 商家报价。一个商家对一个需求单只有一条 —— 改价是改它，不是新开一条。 */
@Getter
@Setter
@TableName("mkt_quote")
public class MktQuote extends BaseEntity {

    private String quoteNo;
    private String requestNo;
    private String merchantNo;
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
