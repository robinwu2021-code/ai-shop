package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

/** Insert-only economic fields; only the head lifecycle may be transitioned by PricingQuoteService. */
@Getter
@Setter
@TableName("ord_price_quote_allocation")
public class OrdPriceQuoteAllocation extends BaseEntity {
    private String allocationNo;
    private String quoteNo;
    private String quoteLineNo;
    private String allocationKind;
    private String stage;
    private Integer stepOrder;
    private String benefitRef;
    private String owner;
    private String ownerQuoteRef;
    private String applicationRef;
    private String ruleNo;
    private String ruleVersion;
    private Long basisMinor;
    private Long amountMinor;
    private Integer payableRateBp;
    private BigDecimal quantityStart;
    private BigDecimal quantityEnd;
    private String algorithmVersion;
    private String evidenceSnapshot;
}
