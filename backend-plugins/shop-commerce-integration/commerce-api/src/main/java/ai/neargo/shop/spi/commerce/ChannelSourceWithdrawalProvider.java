package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.commerce.dto.CommerceInstantDeserializer;
import ai.neargo.shop.commerce.dto.CommerceOwnerDeserializer;
import ai.neargo.shop.commerce.dto.CommerceTextDeserializer;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;

/**
 * Explicit owner withdrawal proof is separate from positive VALID sale qualification; absence is
 * never withdrawal.
 */
public interface ChannelSourceWithdrawalProvider {
    Owner owner();

    Facts capture(Query query);

    enum State {
        ACTIVE,
        WITHDRAWN
    }

    record Disposition(
            Query query,
            @JsonDeserialize(using = WithdrawalStateDeserializer.class) State state,
            Facts withdrawal,
            @JsonDeserialize(using = CommerceInstantDeserializer.class) Instant verifiedAt,
            @JsonDeserialize(using = CommerceInstantDeserializer.class) Instant validUntil,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String ownerReceiptNo) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown disposition field");
        }
    }

    /** Missing capability is unsupported, never an inferred withdrawal or an active default. */
    default Disposition disposition(Query query) {
        throw new UnsupportedOperationException("Original disposition unavailable");
    }

    record Query(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String entityNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String storeNo,
            @JsonDeserialize(using = CommerceOwnerDeserializer.class) Owner owner,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String previousRevision) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown withdrawal query field");
        }
    }

    record Data(
            Query query,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String withdrawalRevision,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String previousContentHash,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String originalActionRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String originalActionHash,
            @JsonDeserialize(using = CommerceInstantDeserializer.class) Instant withdrawnAt) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown withdrawal data field");
        }
    }

    record Facts(
            Data data,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String contentHash,
            @JsonDeserialize(using = CommerceInstantDeserializer.class) Instant verifiedAt,
            @JsonDeserialize(using = CommerceInstantDeserializer.class) Instant validUntil,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String ownerReceiptNo) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown withdrawal facts field");
        }
    }
}
