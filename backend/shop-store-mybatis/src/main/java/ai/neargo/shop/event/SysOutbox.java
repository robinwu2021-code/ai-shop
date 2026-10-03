package ai.neargo.shop.event;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 事务性发件箱。业务与事件<b>同一个事务</b>落库，投递器再异步发出去。
 *
 * <p>为什么不直接在业务方法里发 MQ：那样「订单已支付但事件没发出去」和
 * 「事件发出去了但订单没落库」两种不一致都可能发生，且事后无从追。
 * Outbox 把它收敛成一种情况 —— 事件一定在库里，只是可能还没投递。
 *
 * <p>append-only：不继承 {@code BaseEntity}（无乐观锁、无逻辑删除），
 * 事件是既成事实，只允许改投递状态。
 */
@Data
@TableName("sys_outbox")
public class SysOutbox {

    public static final String PENDING = "PENDING";
    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";


    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventNo;

    /** 聚合类型 + 业务键：出问题时按这两列就能捞出「这个订单发过哪些事件」。 */
    private String aggregateType;
    private String aggregateId;

    private String eventType;

    /** JSON。必须自带消费方所需的全部字段 —— 消费方回查主表会引入新的时序坑。 */
    private String payload;

    /** PENDING / SENT / FAILED */
    private String status;

    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime sentAt;
}
