package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Immutable fence identity preserves the original permission response after reconciliation releases its fence. */
@Getter
@Setter
@TableName("bty_appointment_resource_decision")
public class BtyAppointmentResourceDecision extends BaseEntity {
    private String entityNo;
    private String intentNo;
    private String commandNo;
    private String decisionNo;
    private String fenceNo;
    private Long fenceRevision;
}
