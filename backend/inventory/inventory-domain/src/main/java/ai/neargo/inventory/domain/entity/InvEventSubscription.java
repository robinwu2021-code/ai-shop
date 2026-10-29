package ai.neargo.inventory.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 事件订阅：确认到哪了、清理到哪了。也是同一业主排序器的锁行。 */
@Getter
@Setter
@TableName("inv_event_subscription")
public class InvEventSubscription {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String ownerId;
    private String systemCode;
    private Long ackedSeq;
    private Long prunedSeq;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
