package ai.neargo.shop.printing.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Shared printing persistence; business identifiers alone cross the API boundary. */
@Getter
@Setter
@TableName("prn_job")
public class PrnJob extends BaseEntity {
    private String entityNo;
    private String jobNo;
    private String storeNo;
    private String sourceEventNo;
    private String scene;
    private String deviceNo;
    private String partNo;
    private Integer copyIndex;
    private String templateNo;
    private String documentSnapshot;
    private String renderedContent;
    @com.baomidou.mybatisplus.annotation.TableField("status")
    private String legacyStatus;
    private String deliveryStage;
    @com.baomidou.mybatisplus.annotation.TableField(updateStrategy=com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private LocalDateTime executionReleasedAt;
    private String originalJobNo;
    private String dedupKey;
    private String requestHash;
    private String eventRootKey;
    private String reprintReason;
    private String requestedBy;
    private LocalDateTime leaseUntil;
    private String currentAttemptNo;
    private String leaseToken;
    private Integer attemptCount;

    private String requestNo;
    private String batchNo;
    private String documentKey;
    private String routeRuleKey;
    private String targetKey;
    private Integer required;
    private String printerNo;
    private String templateVersionNo;
    private String rendererVersion;
    private String layoutProfileSnapshot;
    private String layoutSnapshot;
    private String templateResolutionSnapshot;
    private String payloadSnapshot;
    private String documentHash;
    private String routeSnapshot;
    private Integer priority;
    private Long printerSequence;
    private Integer retryCount;
    private Integer maxRetryCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime deadlineAt;
    private String resultEvidence;
    private LocalDateTime confirmedAt;
    private String lastErrorCode;
    private String lastErrorSummary;
    private String parentJobNo;
    private String rootJobNo;
    private String jobKind;
    private String resolutionStatus;
    private String replacementJobNo;
    private LocalDateTime cancelRequestedAt;
    private LocalDateTime finishedAt;
    private String richDocumentSnapshot;
    public Long getRevision() { return getVersion(); }
    public void setRevision(Long revision) { setVersion(revision); }

    public String getStatus() {
        if(deliveryStage!=null) return deliveryStage;
        if(legacyStatus==null) return null;
        return switch(legacyStatus) { case "SUCCESS" -> "COMPLETED"; case "SENT" -> "DISPATCHING"; default -> legacyStatus; };
    }
    public void setStatus(String stage) {
        this.deliveryStage = stage;
        this.legacyStatus = switch (stage) {
            case "COMPLETED" -> "SUCCESS";
            case "DISPATCHING", "ACCEPTED", "PRINTING" -> "SENT";
            case "RETRY_WAIT" -> "PENDING";
            case "CANCELLED", "STALE" -> "UNKNOWN";
            default -> stage;
        };
    }
}
