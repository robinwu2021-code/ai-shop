package ai.neargo.commerce.api.port;

import java.time.Instant;
import ai.neargo.commerce.api.common.*;

public record InboundFact(String tenantNo, String producer, String factNo, String payloadDigest, Instant receivedAt) {}
