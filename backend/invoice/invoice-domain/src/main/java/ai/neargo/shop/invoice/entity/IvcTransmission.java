package ai.neargo.shop.invoice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 发票上传任务（V1）。报文在入队时冻结，补传原样再发。 */
@Getter
@Setter
@TableName("ivc_transmission")
public class IvcTransmission {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String transmissionNo;
    private String tenantNo;
    private String entityNo;
    /** 开立门店（V8） */
    private String storeNo;
    private String regimeCode;
    private String channelAccountNo;
    private String gatewayCode;
    /** ISSUE / VOID / ALLOWANCE */
    private String messageType;
    private String refNo;
    private String payload;
    /** PENDING / SENDING / SENT / FAILED / HELD（账号问题暂停）/ NEEDS_REVIEW（被拒，待人工） */
    private String status;
    private Integer attempts;
    private LocalDateTime nextAttemptAt;
    private String lastError;
    private String gatewayRef;
    /** 预警时点（V8，期限窗口过半） */
    private LocalDateTime reportWarnAt;
    /** 法定上报期限（V8） */
    private LocalDateTime reportDeadlineAt;
    /** 已告警级别（V8）：0 未告警 / 1 已预警 / 2 已逾期或已送达 */
    private Integer deadlineLevel;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
