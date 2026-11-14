package ai.neargo.shop.commerce.dto;

import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.Identity;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.ObjectType;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.SourceContext;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;

/** 公共写入仅表达映射意图；核验回执、主体、门店及MANAGED来源均不接受客户端传入。 */
public final class MappingContracts {
    private MappingContracts() {}

    public record CreateResourceMappingReq(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String connectionNo,
            @JsonDeserialize(using = MappingObjectTypeDeserializer.class) ObjectType objectType,
            Identity externalIdentity,
            @JsonDeserialize(using = CommerceOwnerDeserializer.class) Owner sourceOwner,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceRevision,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String salesOfferRef,
            @JsonInclude(JsonInclude.Include.NON_NULL) SourceContext sourceContext) {
        public CreateResourceMappingReq(
                String connectionNo,
                ObjectType objectType,
                Identity externalIdentity,
                Owner sourceOwner,
                String sourceRef,
                String sourceRevision,
                String salesOfferRef) {
            this(
                    connectionNo,
                    objectType,
                    externalIdentity,
                    sourceOwner,
                    sourceRef,
                    sourceRevision,
                    salesOfferRef,
                    null);
        }

        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw new IllegalArgumentException("Unknown mapping field");
        }
    }

    public record ReviseResourceMappingReq(
            @JsonDeserialize(using = CommerceVersionDeserializer.class) Long version,
            @JsonDeserialize(using = CommerceOwnerDeserializer.class) Owner sourceOwner,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceRevision,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String salesOfferRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String reason,
            @JsonInclude(JsonInclude.Include.NON_NULL) SourceContext sourceContext) {
        public ReviseResourceMappingReq(
                Long version,
                Owner sourceOwner,
                String sourceRef,
                String sourceRevision,
                String salesOfferRef,
                String reason) {
            this(version, sourceOwner, sourceRef, sourceRevision, salesOfferRef, reason, null);
        }

        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw new IllegalArgumentException("Unknown mapping field");
        }
    }

    public record MappingRevisionVO(
            String revisionNo,
            long revision,
            Owner sourceOwner,
            String sourceRef,
            String sourceRevision,
            String salesOfferRef,
            String mappingOrigin,
            String validationStatus,
            String contentHash,
            Instant effectiveAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) SourceContext sourceContext) {
        public MappingRevisionVO(
                String revisionNo,
                long revision,
                Owner sourceOwner,
                String sourceRef,
                String sourceRevision,
                String salesOfferRef,
                String mappingOrigin,
                String validationStatus,
                String contentHash,
                Instant effectiveAt) {
            this(
                    revisionNo,
                    revision,
                    sourceOwner,
                    sourceRef,
                    sourceRevision,
                    salesOfferRef,
                    mappingOrigin,
                    validationStatus,
                    contentHash,
                    effectiveAt,
                    null);
        }
    }

    public record ResourceMappingVO(
            String mappingNo,
            String storeNo,
            String connectionNo,
            ObjectType objectType,
            Identity externalIdentity,
            MappingRevisionVO latest,
            MappingRevisionVO active,
            long version) {}

    public record VerificationBatch(int verified, int pending, int failed) {}
}
