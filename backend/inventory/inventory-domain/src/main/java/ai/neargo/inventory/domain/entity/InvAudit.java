package ai.neargo.inventory.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 写动作审计，只追加。操作人由宿主透传，只记不授权。 */
@Getter
@Setter
@TableName("inv_audit")
public class InvAudit {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String systemCode;
    private String ownerId;
    /** STAFF:E1003 / AGENT:AG001 / SYSTEM:job */
    private String actor;
    private String action;
    private String resourceType;
    private String resourceId;
    private String beforeBody;
    private String afterBody;
    private String reason;
    private String requestId;
    private String traceId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
