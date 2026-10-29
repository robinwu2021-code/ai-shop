package ai.neargo.inventory.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 事件日志：序号在提交后分配，宿主按序号拉取，不漏不重。 */
@Getter
@Setter
@TableName("inv_event_log")
public class InvEventLog {
    @TableId(type = IdType.AUTO)
    private Long seq;
    private String eventId;
    private String ownerId;
    private String eventType;
    private String resourceType;
    private String resourceId;
    private Integer resourceVersion;
    private String payload;
    private String actor;
    private String traceId;
    private LocalDateTime occurredAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
