package ai.neargo.shop.spi.commerce;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Signature verification uses original bytes and a trusted configured endpoint; callback input
 * never selects a tenant.
 */
public interface ChannelInboundGateway extends CommerceGateway {
    final class VerificationRejected extends RuntimeException {
        public VerificationRejected() {
            super("Callback signature rejected");
        }
    }

    Verified verify(Endpoint endpoint, RawMessage message);

    Ack acknowledge(Verified event);

    record Endpoint(
            String entityNo,
            String storeNo,
            String connectionNo,
            String connectionRevisionNo,
            String profileNo,
            String externalAccountRef,
            String externalStoreRef,
            List<Credential> credentials) {
        public Endpoint {
            credentials = List.copyOf(credentials);
        }
    }

    record Credential(String authorizationNo, String credentialRef) {}

    record RawMessage(byte[] bytes, Map<String, String> headers, Instant receivedAt) {
        public RawMessage {
            bytes = bytes.clone();
            headers = Map.copyOf(headers);
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    record Verified(
            String providerEventRef,
            String dedupeIdentity,
            String eventType,
            String externalStoreRef,
            String externalOrderRef,
            String externalRevision,
            Instant occurredAt,
            String authorizationNo,
            String verificationEvidenceRef) {}

    record Ack(int status, String contentType, String body) {}
}
