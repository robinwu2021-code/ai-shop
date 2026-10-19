package ai.neargo.shop.trade.entitlement.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Core-owned coordination facts, never balances. */
@Getter
@Setter
@TableName("ord_ent_order_use")
public class OrdEntOrderUse extends BaseEntity {
    private String operationNo;
    private String orderNo;
    private String entityNo;
    private String userNo;
    private String sourceKey;
    private String payloadHash;
    private String contextSnapshot;
    private String commandSnapshot;
    private String orderSnapshotHash;
    private String status;
    private String decisionSnapshot;
}
