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
    /** 状态取值（零售域状态短值换值表）：只在这里写字面量，别处引用本常量。 */
    public static final String CANCEL_DECIDED = "CANCEL_DECIDED";
    public static final String COMMIT_DECIDED = "COMMIT_DECIDED";
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
