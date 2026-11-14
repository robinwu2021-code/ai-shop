package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** Existing FOOD checkout source metadata; core/PAY remains the authoritative financial ledger. */
@Getter
@Setter
@TableName("fnb_settlement_item")
public class FnbSettlementItem extends BaseEntity {
    private String entityNo;
    private String scopeNo;
    private String settlementNo;
    private String checkNo;
    private String paymentRootNo;
    private String storeOrderNo;
    private String allocationRef;
    private Long dueMinor;
    private String coreVersion;
    private String scopeKey;
}
