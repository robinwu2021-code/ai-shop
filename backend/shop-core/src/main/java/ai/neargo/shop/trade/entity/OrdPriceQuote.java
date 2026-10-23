package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** Insert-only economic fields; only the head lifecycle may be transitioned by PricingQuoteService. */
@Getter
@Setter
@TableName("ord_price_quote")
public class OrdPriceQuote extends BaseEntity {
    private String quoteNo;
    private String entityNo;
    private String storeNo;
    private String subjectNo;
    private String industryCode;
    private String sourceSystem;
    private String sourceType;
    private String sourceNo;
    private Long sourceVersion;
    private String rootScopeNo;
    private Long scopeVersion;
    private String channel;
    private String quotePhase;
    private String storeRevisionNo;
    private String templateRevisionNo;
    private String currency;
    private String engineSpecVersion;
    private String requestHash;
    private String factsHash;
    private String economicsHash;
    private String factsSnapshot;
    private String configSnapshot;
    private String planSnapshot;
    private String resultSnapshot;
    private Integer schemaVersion;
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
    private Long storedPlannedMinor;
    private Long otherPlannedMinor;
    private Long remainingDueMinor;
    private Long cashDueMinor;
    private Long cashTenderMinor;
    private Long cashChangeMinor;
    private String status;
    private LocalDateTime expiresAt;
    private String supersededBy;
    private String retainedRef;
}
