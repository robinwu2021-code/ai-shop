package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Durable source-owned appointment arrangement; not external resource authority. */
@Getter
@Setter
@TableName("bty_appointment_arrangement")
public class BtyAppointmentArrangement extends BaseEntity {
    private String entityNo;
    private String appointmentNo;
    private Long arrangementRevision;
    private String previewNo;
    private String holdNo;
    private String snapshot;
}
