package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Durable source-owned appointment operation; not external resource authority. */
@Getter
@Setter
@TableName("bty_appointment_operation")
public class BtyAppointmentOperation extends BaseEntity {
    private String entityNo;
    private String operationNo;
    private String storeNo;
    private String actorNo;
    private String actorRealm;
    private String actorKind;
    private String keyHash;
    private String requestHash;
    private String appointmentNo;
    private String previewNo;
    private String action;
    private String state;
    private String acceptPayload;
    private String receipt;
    private String result;
    private LocalDateTime nextRetryAt;
    private LocalDateTime leaseUntil;
}
