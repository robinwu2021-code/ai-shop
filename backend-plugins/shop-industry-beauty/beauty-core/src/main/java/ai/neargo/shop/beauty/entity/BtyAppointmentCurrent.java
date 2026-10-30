package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Durable source-owned appointment current; not external resource authority. */
@Getter
@Setter
@TableName("bty_appointment_current")
public class BtyAppointmentCurrent extends BaseEntity {
    private String entityNo;
    private String appointmentNo;
    private Long arrangementRevision;
    private String operationNo;
}
