package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

/** Insert-only economic fields; only the head lifecycle may be transitioned by PricingQuoteService. */
@Getter
@Setter
@TableName("ord_price_quote_line")
public class OrdPriceQuoteLine extends BaseEntity {
    private String quoteLineNo;
    private String quoteNo;
    private String pricingUnitRef;
    private String sourceLineNo;
    private String parentSourceLineNo;
    private String componentRef;
    private String invoiceLineRef;
    private String goodsNo;
    private String skuNo;
    private String lineKind;
    private BigDecimal quantityStart;
    private BigDecimal quantityEnd;
    private BigDecimal quantity;
    private Long unitPriceMinor;
    private Long goodsMinor;
    private Long priceAdjustmentMinor;
    private Long activityDiscountMinor;
    private Long couponDiscountMinor;
    private Long manualDiscountMinor;
    private Long pointsDiscountMinor;
    private Long waiverMinor;
    private Long feeMinor;
    private Long netMinor;
    private Long taxMinor;
    private Long receivableMinor;
    private Long considerationOffsetMinor;
    private Long invoiceBaseMinor;
    private String taxClass;
    private String eligibilitySnapshot;
    private String sourceSnapshot;
    private Integer sortOrder;
}
