package ai.neargo.shop.trade.entitlement.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Core counter-service source; this row is not an ordinary cash order or a payment receipt. */
@Getter
@Setter
@TableName("ord_ent_service_order")
public class OrdEntServiceOrder extends BaseEntity {
    /** 状态取值（零售域状态短值换值表）：只在这里写字面量，别处引用本常量。 */
    public static final String CANCEL_DECIDED = "CANCEL_DECIDED";
    public static final String COMMIT_DECIDED = "COMMIT_DECIDED";
    private String serviceOrderNo;
    private String operationNo;
    private String entityNo;
    private String storeNo;
    private String userNo;
    private String operatorNo;
    private String requestKeyHash;
    private String payloadHash;
    private String sourceSnapshot;
    private String sourceHash;
    private String status;
    private String contextSnapshot;
    private String previewSnapshot;
    private LocalDateTime previewExpiresAt;
    private String commandSnapshot;
    private String decisionSnapshot;
    private String refundStatus;
    private String refundActorNo;
    private String refundCommandSnapshot;
    private String cancellationReason;
    private LocalDateTime deadline;
}
