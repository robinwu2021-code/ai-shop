package ai.neargo.commerce.api.port;

import java.time.Instant;
import ai.neargo.commerce.api.common.*;

/** Version is the persisted aggregate authority version; schemaVersion identifies the wire schema. */
public record CommerceEvent(String eventNo, String tenantNo, String aggregateType, String aggregateNo, long aggregateVersion, String eventType, int schemaVersion, String payloadJson, Instant occurredAt) {}
