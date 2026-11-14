package ai.neargo.shop.commerce.dto;

import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Document;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;
import java.util.List;

/** 本批类型化目录契约；公共请求没有主体、价格、库存或远端完成证明字段。 */
public final class CatalogContracts {
    private CatalogContracts() {}

    public record SourceReq(
            @JsonDeserialize(using = CommerceOwnerDeserializer.class) Owner sourceOwner,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String selectionRevision,
            @JsonDeserialize(using = CommerceIntegerDeserializer.class) Integer priority) {
        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw new IllegalArgumentException("Unknown catalog field");
        }
    }

    public record CreateCatalogScopeReq(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String connectionNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String remoteScopeKey,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String name,
            List<SourceReq> sourceRefs) {
        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw new IllegalArgumentException("Unknown catalog field");
        }
    }

    public record SaveCatalogSourcesReq(
            @JsonDeserialize(using = CommerceVersionDeserializer.class) Long version,
            List<SourceReq> sourceRefs) {
        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw new IllegalArgumentException("Unknown catalog field");
        }
    }

    public record PreviewCatalogReq(
            @JsonDeserialize(using = CommerceVersionDeserializer.class) Long expectedScopeVersion) {
        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw new IllegalArgumentException("Unknown catalog field");
        }
    }

    public record SubmitSyncRunReq(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String scopeNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String snapshotNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String hash,
            @JsonDeserialize(using = CommerceVersionDeserializer.class) Long expectedScopeVersion,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String reason) {
        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw new IllegalArgumentException("Unknown catalog field");
        }
    }

    public record SaveSyncPolicyReq(
            @JsonDeserialize(using = CommerceVersionDeserializer.class) Long version,
            @JsonDeserialize(using = CommerceBooleanDeserializer.class) Boolean enabled,
            @JsonDeserialize(using = CommerceBooleanDeserializer.class) Boolean onPublish,
            @JsonDeserialize(using = CommerceBooleanDeserializer.class) Boolean onAvailability,
            @JsonDeserialize(using = CommerceBooleanDeserializer.class) Boolean scheduledCheck,
            @JsonDeserialize(using = CommerceIntegerDeserializer.class) Integer debounceSeconds,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String recoveryProfile) {
        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw new IllegalArgumentException("Unknown catalog field");
        }
    }

    public record CancelQueuedSyncReq(
            @JsonDeserialize(using = CommerceVersionDeserializer.class) Long version,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String reason) {
        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw new IllegalArgumentException("Unknown catalog field");
        }
    }

    public record CatalogScopeVO(
            String scopeNo,
            String storeNo,
            String connectionNo,
            String name,
            List<SourceReq> sources,
            String desiredSnapshotNo,
            String lastConfirmedSnapshotNo,
            String remoteConsistency,
            long generation,
            String activeOperationNo,
            long version) {}

    public record CatalogPreviewVO(
            String snapshotNo,
            String scopeNo,
            String hash,
            String currency,
            long itemCount,
            Instant expiresAt,
            long scopeVersion,
            Document document) {}

    public record SyncPolicyVO(
            String policyNo,
            String scopeNo,
            boolean enabled,
            boolean onPublish,
            boolean onAvailability,
            boolean scheduledCheck,
            int debounceSeconds,
            String recoveryProfile,
            long version) {}

    public record SyncRunVO(
            String runNo,
            String scopeNo,
            String connectionNo,
            String snapshotNo,
            String type,
            String triggerType,
            String status,
            Long generation,
            String operationNo,
            String desiredSnapshotNo,
            String lastConfirmedSnapshotNo,
            String remoteConsistency,
            String failureCode,
            long version) {}

    public record SyncRunItemVO(
            String itemNo,
            long attemptNo,
            String objectType,
            String externalRef,
            String sourceRef,
            String result,
            String errorCode,
            String evidenceRef) {}

    public record RecoveryVO(int dispatched, int queried, int automatic, int pending, int failed) {}
}
