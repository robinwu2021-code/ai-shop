package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** Existing FOOD checkout source metadata; core/PAY remains the authoritative financial ledger. */
@Getter
@Setter
@TableName("fnb_charge_intent")
public class FnbChargeIntent extends BaseEntity {
    private String entityNo;
    private String intentNo;
    private String settlementNo;
    private String kind;
    private String requestKey;
    private String coreReference;
    private String status;
    private String requestHash;
    private String cashShiftNo;
    private String requestSnapshot;
}
