package ai.neargo.shop.invoice.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("ivc_transmission_attempt")
public class IvcTransmissionAttempt {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantNo;
    private String entityNo;
    private String storeNo;
    private String attemptNo;
    private String transmissionNo;
    private Long attemptIndex;
    private String operation;
    private String refNo;
    private String gatewayCode;
    private String channelAccountNo;
    private Long accountRevision;
    private String actorNo;
    private String phase;
    private String outcome;
    private String safeCode;
    private String payloadHash;
    private LocalDateTime occurredAt;
    /** 创建人；不显式给就由公共字段填充器写当前操作人 */
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
    /** 更新人 */
    @TableField(fill = FieldFill.INSERT)
    private String updatedBy;
}
