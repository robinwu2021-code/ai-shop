package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;
import java.util.Map;

import java.time.LocalDateTime;

/**
 * Permanent authentic original relationship; observation fields never authorize a new human
 * acceptance.
 */
@Getter
@Setter
@TableName("bty_service_execution_stock_intent")
public class BtyServiceExecutionStockIntent extends BaseEntity {
    /** 观察状态落库用短值（零售域状态短值换值表 §3.9，不超过 10 个字符）；对外的准备进度仍用原名，只在这里换。 */
    private static final Map<String, String> OBSERVATION = Map.of(
            "SOURCE_SUSPENDED", "SRC_SUSP",
            "STOCK_RETENTION_PENDING", "TO_RETAIN",
            "STOCK_RETAINED", "RETAINED",
            "CONFLICT", "CONFLICT");

    /** 准备进度名的落库值；不认识的名字抛错。 */
    public static String observationCode(String state) {
        String code = OBSERVATION.get(state);
        if (code == null) throw new IllegalStateException("unknown observation_state " + state);
        return code;
    }

    /** 落库值还原为准备进度名。 */
    public static String observationOf(String code) {
        return OBSERVATION.entrySet().stream().filter(e -> e.getValue().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalStateException("unknown observation_state " + code)).getKey();
    }

    private String entityNo;
    private String storeNo;
    private String workOrderNo;
    private String taskNo;
    private String fenceNo;
    private String submissionNo;
    private String executionNo;
    private String resourceCommandNo;
    private Integer attemptNo;
    private String bindingHash;
    private String sourceIntentHash;
    private String resourceCommandHash;
    private String planHash;
    private String referenceHash;
    private String lookupHash;
    private String retentionNo;
    private String requestHash;
    private String referenceJson;
    private String lookupJson;
    private String routeNo;
    private String routeHash;
    private String evidenceHash;
    private String receiptHash;
    private String evidenceJson;
    private String receiptJson;
    private LocalDateTime evidenceObservedAt;
    private LocalDateTime receiptObservedAt;
    private String observationState;
    private String pendingReason;
    private Long sourceFenceRevision;
    private Long observationEpoch;
}
