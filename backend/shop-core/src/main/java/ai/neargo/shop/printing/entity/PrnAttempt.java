package ai.neargo.shop.printing.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Shared printing persistence; business identifiers alone cross the API boundary. */
@Getter
@Setter
@TableName("prn_attempt")
public class PrnAttempt extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String attemptNo;
    private String jobNo;
    private Integer sequenceNo;
    private String channelJobNo;
    private String status;
    private String responseCode;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String leaseToken;
    private String bridgeActorNo;

    private String printerNo;
    private Integer attemptIndex;
    private String dispatchKey;
    private String agentNo;
    private Long agentSessionEpoch;
    private Long fenceEpoch;
    private Long connectionRevision;
    private String credentialRef;
    private String connectionSnapshot;
    private String adapterVersion;
    private String providerScope;
    private String providerJobId;
    private String providerReferenceHash;
    private String requestHash;
    private String transportHash;
    private LocalDateTime submittedAt;
    private LocalDateTime acceptedAt;
    private LocalDateTime resultObservedAt;
    private LocalDateTime nextQueryAt;
    private String lastErrorCode;
    private String resultSummary;
    public Long getRevision() { return getVersion(); }
    public void setRevision(Long revision) { setVersion(revision); }
    private Long authorizationEpoch;
}
