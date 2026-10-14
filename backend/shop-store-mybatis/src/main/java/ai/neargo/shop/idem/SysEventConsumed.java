package ai.neargo.shop.idem;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 事件级幂等记录。见 {@link EventIdempotency} 与 V284 迁移的注释 */
@TableName("sys_event_consumed")
public class SysEventConsumed {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventNo;

    /** 消费者名。同一个事件被多个消费者各处理一次是正常的，所以它进唯一键 */
    private String handler;

    private LocalDateTime consumedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEventNo() { return eventNo; }
    public void setEventNo(String eventNo) { this.eventNo = eventNo; }
    public String getHandler() { return handler; }
    public void setHandler(String handler) { this.handler = handler; }
    public LocalDateTime getConsumedAt() { return consumedAt; }
    public void setConsumedAt(LocalDateTime consumedAt) { this.consumedAt = consumedAt; }
}
