package ai.neargo.shop.food.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.time.Instant;
import java.util.List;

/** Device draft evidence only; none of these records carries money, payment or stock authority. */
public final class OfflineDraftContracts {
    private OfflineDraftContracts() {}

    public interface Strict {
        @JsonAnySetter
        default void unknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown offline draft field");
        }
    }

    public record ChallengeRequest(String publicKey, String credential) implements Strict {
        @Override
        public String toString() {
            return "ChallengeRequest[REDACTED]";
        }
    }

    public record Challenge(
            String challengeNo,
            String entityNo,
            String storeNo,
            String deviceNo,
            long deviceGeneration,
            String publicKeyHash,
            String nonce,
            Instant validUntil)
            implements Strict {}

    public record Enroll(String challengeNo, String signature, String credential)
            implements Strict {
        @Override
        public String toString() {
            return "Enroll[REDACTED]";
        }
    }

    public record Signer(
            String keyNo,
            String entityNo,
            String storeNo,
            String deviceNo,
            long deviceGeneration,
            String publicKeyHash)
            implements Strict {}

    public record LeaseRequest(
            String keyNo,
            long expectedFence,
            int lifetimeSeconds,
            String requestKey,
            String signature)
            implements Strict {}

    /** Signature input excludes only the signature itself; domain is FOOD_OFFLINE_LEASE_V1. */
    public record LeaseProof(
            String entityNo,
            String storeNo,
            String deviceNo,
            long deviceGeneration,
            String keyNo,
            long expectedFence,
            int lifetimeSeconds,
            String requestKey)
            implements Strict {}

    public record Lease(
            String leaseNo,
            String entityNo,
            String storeNo,
            String deviceNo,
            long deviceGeneration,
            String keyNo,
            long fence,
            Instant validUntil,
            long acceptedThrough,
            String capability)
            implements Strict {}

    /**
     * Entire immutable envelope is signed under FOOD_OFFLINE_DRAFT_V1; sequence starts at one per
     * lease.
     */
    public record Envelope(
            String entityNo,
            String storeNo,
            String deviceNo,
            long deviceGeneration,
            String keyNo,
            String leaseNo,
            long fence,
            long sequence,
            String fulfillment,
            List<OrderLineInput> lines)
            implements Strict {
        public Envelope {
            lines = lines == null ? null : List.copyOf(lines);
        }
    }

    public record Upload(Envelope envelope, String signature) implements Strict {}

    public record Replay(String draftNo, CartAccess access) implements Strict {
        @Override
        public String toString() {
            return "Replay[draftNo=" + draftNo + ",access=REDACTED]";
        }
    }

    public record Receipt(
            String receiptNo,
            String draftNo,
            String status,
            long acceptedThrough,
            String payloadHash,
            String cartNo,
            int appliedLines,
            String reason)
            implements Strict {}
}
