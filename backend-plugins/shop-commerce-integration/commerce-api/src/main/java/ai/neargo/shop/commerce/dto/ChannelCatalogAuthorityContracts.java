package ai.neargo.shop.commerce.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;

/**
 * Original connection and grant evidence carries no credential references or editable authorization
 * material.
 */
public final class ChannelCatalogAuthorityContracts {
    private ChannelCatalogAuthorityContracts() {}

    public record Query(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String entityNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String storeNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String connectionNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String revisionNo) {
        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw unknown();
        }
    }

    public record Provider(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String providerNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String providerCode,
            @JsonDeserialize(using = CommerceVersionDeserializer.class) Long version) {
        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw unknown();
        }
    }

    public record Profile(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String profileNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String productCode,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String market,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String protocolCode,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String identityNamespace,
            @JsonDeserialize(using = CommerceBooleanDeserializer.class) Boolean accountScoped,
            @JsonDeserialize(using = CommerceVersionDeserializer.class) Long version) {
        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw unknown();
        }
    }

    public record Account(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String accountNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String externalAccountRef,
            @JsonDeserialize(using = CommerceVersionDeserializer.class) Long version) {
        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw unknown();
        }
    }

    public record Connection(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String connectionNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String revisionNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String externalStoreRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String channelStoreKey,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceOwner,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String catalogAuthority,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String fulfillmentProfile,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String configHash,
            @JsonDeserialize(using = CommerceVersionDeserializer.class) Long version,
            @JsonDeserialize(using = CommerceVersionDeserializer.class) Long revisionVersion,
            @JsonDeserialize(using = CommerceVersionDeserializer.class) Long revision) {
        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw unknown();
        }
    }

    public record Grant(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String authorizationNo,
            @JsonDeserialize(using = CommerceVersionDeserializer.class) Long version,
            @JsonDeserialize(using = CommerceVersionDeserializer.class) Long revision,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String capability,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String capabilityHash,
            Instant expiresAt) {
        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw unknown();
        }
    }

    public record IdentityEvidence(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String status,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String identityHash,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String receiptRef,
            Instant verifiedAt,
            Instant validUntil) {
        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw unknown();
        }
    }

    public record Facts(
            @JsonDeserialize(using = CommerceIntegerDeserializer.class) Integer schemaVersion,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String purpose,
            Query query,
            Provider provider,
            Profile profile,
            Account account,
            Connection connection,
            Grant grant,
            IdentityEvidence identity,
            Instant verifiedAt,
            Instant validUntil,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String receiptNo) {
        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw unknown();
        }
    }

    private static IllegalArgumentException unknown() {
        return new IllegalArgumentException("Unknown catalog authority field");
    }
}
