package ai.neargo.shop.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Permanent original association; mutable observation uses an explicit owner CAS revision. */
@Getter
@Setter
@TableName("inv_execution_retention")
public class InvExecutionRetention extends InvMutableEntity {
    private String retentionNo;

    private String componentNo;

    private String componentDescriptorHash;

    private String requestHash;

    private String referenceHash;

    private String sourceTenantNo;

    private String sourceEntityNo;

    private String sourceStoreNo;

    private String executionNo;

    private String unitNo;

    private String grantNo;

    private Integer attemptNo;

    private Long preparedGrantRevision;

    private String referenceSchema;

    private String referenceJson;

    private String parentOriginalJson;

    private String parentOriginalHash;

    private String parentManifestHash;

    private String componentDescriptorJson;

    private String componentManifestHash;

    private String proofMode;

    private String sourceConfirmationNo;

    private String externalStockConfirmationOperationNo;

    private String mirrorProofJson;

    private String mirrorProofHash;

    private String acceptedEvidenceHash;

    private String acceptedAuthorityJson;

    private String acceptedAuthorityHash;

    private String resourceCommandNo;

    private String resourceCommandHash;

    private String planHash;

    private String ownerSetHash;

    private Integer ownerSetCount;

    private String state;

    private String issueObservationJson;

    private String issueObservationHash;

    private String noEffectSealJson;

    private String noEffectSealNo;

    private Long noEffectSealRevision;

    private String noEffectSealHash;

    private Long retentionRevision;

    private String ownerId;

    private String reservationId;

    private Long originalLineId;

    private Integer originalLineNo;

    private String itemId;

    private String locationId;

    private String sourceSkuNo;

    private String fulfillmentRef;

    private Integer lifecycleVersion;

    private Integer originalQty;

    private String nativeConfirmationOperationNo;

    private Long retainedDispositionRevision;

    private String retainedDisposition;

    private String originalRowJson;

}
