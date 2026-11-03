package ai.neargo.commerce.api.port;

import java.time.Instant;
import ai.neargo.commerce.api.common.*;

/** Trusted producer identity and content digest; raw inbound payload is retained by its host source. */
public record InboundFact(String tenantNo, String producer, String factNo, String eventType, String aggregateNo, String payloadDigest, Instant receivedAt) {}
