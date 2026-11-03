package ai.neargo.commerce.api.port;

import java.time.Instant;
import ai.neargo.commerce.api.common.*;

public record CommerceEvent(String eventNo, String tenantNo, String aggregateType, String aggregateNo, String eventType, int schemaVersion, String payloadJson, Instant occurredAt) {}
