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
    private String regimeCode;
    private String gatewayCode;
    /** ISSUE / VOID / ALLOWANCE */
    private String messageType;
    private String refNo;
    private String payload;
    /** PENDING / SENDING / SENT / FAILED */
    private String status;
    private Integer attempts;
    private LocalDateTime nextAttemptAt;
    private String lastError;
    private String gatewayRef;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
