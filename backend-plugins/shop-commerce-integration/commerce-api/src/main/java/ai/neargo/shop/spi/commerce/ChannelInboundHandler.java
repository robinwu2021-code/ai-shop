package ai.neargo.shop.spi.commerce;

import java.time.Instant;

/**
 * Durable callback handling is replayable by eventNo; handlers must use that key at every owner
 * boundary.
 */
public interface ChannelInboundHandler {
    String profileNo();

    boolean supports(String eventType);

    default boolean acceptsProfile(String profile) {
        return profileNo().equals(profile);
    }

    Result handle(Event event);

    enum Outcome {
        PROCESSED,
        IGNORED,
        RETRY,
        WAITING_OWNER,
        CONFLICT
    }

    record Event(
            String eventNo,
            String entityNo,
            String storeNo,
            String connectionNo,
            String connectionRevisionNo,
            String profileNo,
            String externalAccountRef,
            String externalStoreRef,
            String eventType,
            String providerEventRef,
            String externalOrderRef,
            String externalRevision,
            Instant occurredAt,
            String authorizationNo,
            String credentialRef,
            String verificationEvidenceRef,
            byte[] originalBody) {
        public Event {
            originalBody = originalBody.clone();
        }

        @Override
        public byte[] originalBody() {
            return originalBody.clone();
        }
    }

    record Result(Outcome outcome, String resultRef) {}
}
