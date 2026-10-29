package ai.neargo.shop.invbridge.entity;

import java.sql.Timestamp;
import lombok.Getter;
import lombok.Setter;

/** Native outbox read model preserves JDBC timestamp conversion, with no mutable delivery-state authority. */
@Getter
@Setter
public final class ExecutionRetentionMirrorEvent {
    private Long id;
    private String eventNo;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    private String payload;
    private Timestamp createdAt;
    private String sourceSystem;
    private String sourceEventNo;
    private Integer schemaVersion;
    private String payloadHash;
    private String streamKey;
    private Long streamSequence;
    private String routingMode;
    private Timestamp occurredAt;
}
