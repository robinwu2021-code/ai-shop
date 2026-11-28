package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.GrantState;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.IntentState;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/** Permanent original ownership survives source refresh and task terminal history. */
@Getter
@Setter
@TableName("bty_service_execution_fence")
public class BtyServiceExecutionFence extends BaseEntity {
    /**
     * 意向与许可状态落库用短值（零售域状态短值换值表 §3.9，不超过 10 个字符）；接口枚举名不变，只在这里换。
     * 意向状态与服务任务状态同一套写法。
     */
    private static final Map<IntentState, String> INTENT = Map.of(
            IntentState.FROZEN, "FROZEN",
            IntentState.EXECUTION_PENDING, BtyServiceTask.EXECUTION_PENDING,
            IntentState.PREPARED_OBSERVED, BtyServiceTask.PREPARED_OBSERVED,
            IntentState.REJECTED, "REJECTED",
            IntentState.SUSPEND_REQUESTED, BtyServiceTask.SUSPEND_REQUESTED);
    private static final Map<GrantState, String> GRANT = Map.of(
            GrantState.PREPARED, "PREPARED",
            GrantState.SUSPEND_REQUESTED, "TO_SUSPEND",
            GrantState.STARTED, "STARTED",
            GrantState.STARTED_SUSPENDED, "START_SUSP",
            GrantState.RELEASING, "RELEASING",
            GrantState.RELEASED, "RELEASED",
            GrantState.FULFILLED, "FULFILLED");

    /** 意向状态的落库值。 */
    public static String intentCode(IntentState state) {
        return INTENT.get(state);
    }

    /** 落库值还原为意向状态；不认识的值抛错。 */
    public static IntentState intentOf(String code) {
        return INTENT.entrySet().stream().filter(e -> e.getValue().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalStateException("unknown intent_state " + code)).getKey();
    }

    /** 许可状态的落库值。 */
    public static String grantCode(GrantState state) {
        return GRANT.get(state);
    }

    /** 落库值还原为许可状态；为空时返回空。 */
    public static GrantState grantOf(String code) {
        if (code == null) return null;
        return GRANT.entrySet().stream().filter(e -> e.getValue().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalStateException("unknown observed_grant_state " + code)).getKey();
    }

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
