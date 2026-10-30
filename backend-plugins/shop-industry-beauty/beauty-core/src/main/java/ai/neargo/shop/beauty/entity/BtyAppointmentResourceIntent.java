package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 内部持久化事实；不代表来源许可或资源权威。 */
@Getter
@Setter
@TableName("bty_appointment_resource_intent")
public class BtyAppointmentResourceIntent extends BaseEntity {
    /** 可信经营主体。 */
    private String entityNo;
    /** 意图号。 */
    private String intentNo;
    /** 预约根。 */
    private String appointmentNo;
    /** 门店。 */
    private String storeNo;
    /** 共用命令号。 */
    private String commandNo;
    /** 来源预约版本。 */
    private Long sourceRevision;
    /** 资源动作。 */
    private String action;
    /** 完整命令摘要。 */
    private String commandHash;
    /** 原始计划字节摘要。 */
    private String planHash;
    /** 保留原始UTF8字节；不能用JSON列归一化。 */
    private String frozenPlan;
    /** 0准备1许可2拒绝3已对账。 */
    private Integer phaseCode;
    /** 发起主体。 */
    private String initiatedBy;
    /** 来源决定。 */
    private String decisionNo;
    /** 许可起始。 */
    private LocalDateTime issuedAt;
    /** 许可截止；不是栅栏释放条件。 */
    private LocalDateTime validUntil;
    /** 1成功2拒绝。 */
    private Integer terminalCode;
    /** 拒绝安全原因；拒绝不携带许可窗口。 */
    private String reasonCode;
    /** 权威终态摘要。 */
    private String terminalHash;
}
