package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 预约的资源命令（门店交易库 {@code ord_appointment_command}，TDD §2.6 第 4–6 项方向 A）：一次变更发给资源方的每条命令一行。
 * 合并原资源意向、资源决策与预约操作里的受理载荷、回执绑定；许可一经写入不再改，重放原样答回。
 */
@Getter
@Setter
@TableName("ord_appointment_command")
public class BtyAppointmentCommand extends BaseEntity {
    /** 状态（短值，不超过 10 个字符）：只在这里写字面量，别处引用本常量。 */
    public static final String SENT = "SENT";
    public static final String BOUND = "BOUND";
    public static final String PERMITTED = "PERMITTED";
    public static final String REFUSED = "REFUSED";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String REJECTED = "REJECTED";

    /** 共用表的 {@code tenant_no} 平台审计不填：在这里带上。 */
    public BtyAppointmentCommand() {
        setTenantNo("MAIN");
    }
    private String entityNo;
    private String storeNo;
    private String appointmentNo;
    private String changeNo;
    /** 发给宿主的命令键：变更号 + 动作。 */
    private String requestKey;
    /** RESERVE、CONFIRM、REPLACE、RELEASE。 */
    private String action;
    private Long sourceRevision;
    private String acceptPayload;
    private String commandNo;
    private String commandHash;
    private String planHash;
    private String receipt;
    private String status;
    private String decisionNo;
    private LocalDateTime permittedAt;
    private LocalDateTime validUntil;
    private String fenceNo;
    private Long fenceRevision;
    private String rejectCode;
    private String resultHash;
}
