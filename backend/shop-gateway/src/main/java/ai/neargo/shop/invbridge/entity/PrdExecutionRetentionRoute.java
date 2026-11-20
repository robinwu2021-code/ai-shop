package ai.neargo.shop.invbridge.entity;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** One owner-local coordination relation; no quantity or stock balance. */
@Getter
@Setter
public final class PrdExecutionRetentionRoute {
    private Long id;
    private String tenantNo;
    private String entityNo;
    private String storeNo;
    private String executionNo;
    private String unitNo;
    private String grantNo;
    private Integer attemptNo;
    private Long preparedGrantRevision;
    private String routeNo;
    private String routeHash;
    private String retentionNo;
    private String requestHash;
    private String referenceHash;
    private String parentOriginalHash;
    private String parentManifestHash;
    private String stockConfirmationOperationNo;
    private String sourceEvidenceHash;
    private String componentRosterHash;
    private String acceptedIntentNo;
    private String acceptedIntentHash;
    private String originalHumanAuthorityHash;
    private String resourceCommandHash;
    private String planHash;
    private String routeSchema;
    private String referenceSchema;
    private String frozenAuthority;
    private String sourceConfirmationNo;
    private String resourceCommandNo;
    private Integer componentRosterCount;
    private String routeJson;
    private String referenceJson;
    private String parentOriginalJson;
    private String sourceEvidenceJson;
    private String componentRosterJson;
    private String acceptedIntentJson;
    private String originalHumanAuthorityJson;
    private String mirrorOriginalJson;
    private String mirrorOriginalHash;
    private String state;
    private Long observationEpoch;
    private Long revision;
    private String observationsJson;
    private String observationsHash;
    private LocalDateTime nextRetryAt;
    private LocalDateTime createdAt;
    private String createdBy;
    private LocalDateTime updatedAt;
    private String updatedBy;
    private Long version;
    private Integer deleted;
}
