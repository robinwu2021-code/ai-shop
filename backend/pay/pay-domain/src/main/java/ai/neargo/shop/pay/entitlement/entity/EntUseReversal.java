package ai.neargo.shop.pay.entitlement.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("ent_use_reversal")
public class EntUseReversal extends BaseEntity {
    private String reverseNo;
    private String usageNo;
    private String originOperationNo;
    private String instanceNo;
    private String afterSaleNo;
    private String sourceDecisionNo;
    private String payloadHash;
    private String commandSnapshot;
    private Integer units;
    private Long discountMinor;
    private LocalDateTime occurredAt;
}
