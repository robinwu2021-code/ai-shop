package ai.neargo.inventory.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 待排序事件：业务事务里写，提交后由排序器搬进 {@link InvEventLog} 并分配序号。 */
@Getter
@Setter
@TableName("inv_event_pending")
public class InvEventPending {
    @TableId(type = IdType.AUTO)
    private Long id;
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
}
