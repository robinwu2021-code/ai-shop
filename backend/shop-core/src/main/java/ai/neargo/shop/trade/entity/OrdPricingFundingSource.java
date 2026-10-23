package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Immutable quote binding; lifecycle changes never revise the economic source identity. */
@Getter
@Setter
@TableName("ord_pricing_funding_source")
public class OrdPricingFundingSource extends BaseEntity {
    private String fundingSourceNo;
    private String quoteNo;
    private String entityNo;
    private String storeNo;
    private String userNo;
    private String currency;
    private Long amountMinor;
    private String economicsHash;
    private String preparationHash;
    private String snapshotHash;
    private String status;
    private LocalDateTime expiresAt;
    private String cancelReason;
}
