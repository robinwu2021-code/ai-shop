package ai.neargo.shop.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * App 外收款记录的一步留痕：CREATE / CORRECT / VOID，带前后值、原因与操作人。
 *
 * <p>只插不改，所以不继承带乐观锁与逻辑删除的 BaseEntity —— 表里也没有那几列。
 */
@Getter
@Setter
@TableName("ord_offline_record_event")
public class OrdOfflineRecordEvent {
    public static final String CREATE = "CREATE";
    public static final String CORRECT = "CORRECT";
    public static final String VOID = "VOID";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantNo;
    private String recordNo;
    private String action;
    private Long fromAmountMinor;
    private Long toAmountMinor;
    private String fromMethod;
    private String toMethod;
    private LocalDateTime fromReceivedAt;
    private LocalDateTime toReceivedAt;
    private String reason;
    private String actorNo;
    private LocalDateTime occurredAt;
    private LocalDateTime createdAt;
}
