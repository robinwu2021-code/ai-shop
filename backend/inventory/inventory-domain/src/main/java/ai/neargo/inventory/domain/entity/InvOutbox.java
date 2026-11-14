package ai.neargo.inventory.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 领域事件出站：投递侧将来换 MQ，写入侧不动 */
@Getter
@Setter
@TableName("inv_outbox")
public class InvOutbox extends InvMutableEntity {

    private String eventNo;

    private String ownerId;

    /** DocumentPosted / StockBalanceChanged / ReservationExpired / LowStockDetected */
    private String eventType;

    /** JSON */
    private String payload;

    /** PENDING / SENT / FAILED */
    private String status;

    private Integer retryCount;

    private LocalDateTime nextRetryAt;

    private String lastError;

    private LocalDateTime sentAt;

    /** Unified protocol; null on legacy events not yet adopted. */
    private String sourceSystem;
    private String sourceEventNo;
    private Integer schemaVersion;
    private String payloadHash;
    private String streamKey;
    private Long streamSequence;
    private String routingMode;
    private LocalDateTime occurredAt;
    private String aggregateType;
    private String aggregateId;
}
