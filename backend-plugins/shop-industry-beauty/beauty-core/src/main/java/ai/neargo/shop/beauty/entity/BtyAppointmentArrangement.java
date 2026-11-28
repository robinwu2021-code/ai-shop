package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Durable source-owned appointment arrangement; not external resource authority. */
@Getter
@Setter
@TableName("ord_appointment_revision")
public class BtyAppointmentArrangement extends BaseEntity {
    /** 存在门店交易库 {@code ord_appointment_revision}（§2.6 第 3 项，预约修订；预约准备随第 4 项并入）。共用表的 {@code tenant_no} 没有库默认值，平台审计也不填它：在这里带上。 */
    public BtyAppointmentArrangement() {
        setTenantNo("MAIN");
    }
    private String entityNo;
    private String appointmentNo;
    private Long arrangementRevision;
    private String previewNo;
    private String holdNo;
    private String snapshot;
}
