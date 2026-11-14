package ai.neargo.commerce.api.port;

import java.time.Instant;
import ai.neargo.commerce.api.common.*;

public record AuditEntry(String operationNo, String objectType, String objectNo, ActorContext actor, String reason, long beforeVersion, long afterVersion, String resultCode, Instant occurredAt) {}
