package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Durable source-owned appointment preparation; not external resource authority. */
@Getter
@Setter
@TableName("bty_appointment_preparation")
public class BtyAppointmentPreparation extends BaseEntity {
    private String entityNo;
    private String previewNo;
    private String storeNo;
    private String actorNo;
    private String actorRealm;
    private String actorKind;
    private String customerRef;
    private String guestRef;
    private String appointmentNo;
    private Long expectedRevision;
    private String policyVersionNo;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private String timezone;
    private String frozenPlan;
    private String planHash;
    private String snapshot;
    private LocalDateTime expiresAt;
}
