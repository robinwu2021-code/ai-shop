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
    private String cancellationReason;
    private LocalDateTime deadline;
}
