package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Permanent original ownership survives source refresh and task terminal history. */
@Getter
@Setter
@TableName("bty_service_execution_fence")
public class BtyServiceExecutionFence extends BaseEntity {
    private String fenceNo;
    private String entityNo;
    private String storeNo;
    private String workOrderNo;
    private String taskNo;
    private String bindingNo;
    private String executionNo;
    private String submissionNo;
    private String definitionVersionNo;
    private String resourceCommandNo;
    private String sourceNo;
    private String orderNo;
    private String sourceOperationNo;
    private String sourceKind;
    private String sourceLineNo;
    private Long bindingRevision;
    private Long manualSourceRevision;
    private Long manualSourceEpoch;
    private Long initialTaskRevision;
    private Long scopeRevision;
    private Integer unitOrdinal;
    private Integer orderedUnits;
    private Integer attemptNo;
    private Integer quantity;
    private String uom;
    private String manualSourceHash;
    private String sourceIntentHash;
    private String bindingHash;
    private String payloadHash;
    private String executionSnapshotHash;
    private String planHash;
    private String resourceCommandHash;
    private String intentState;
    private Long sourceFenceRevision;
    private Long observationEpoch;
    private LocalDateTime nextRetryAt;
    private String observedGrantNo;
    private Long observedGrantRevision;
    private String observedGrantState;
    private Long suspensionIntentRevision;
    private Long suspensionManualEpoch;
    private Long suspensionObservationVersion;
    private Long suspensionObservedRevision;
    private String suspensionObservedHash;
    private String suspensionReason;
    private String suspensionProofHash;
    private String bindingJson;
}
