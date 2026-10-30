package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 资源持久命令与结果；业务写入须由后续仲裁事务维护，通用Mapper不等于业务协议。 */
@Getter
@Setter
@TableName("mch_resource_command")
public class MchResourceCommand extends BaseEntity {
    /** 可信经营主体。 */
    private String entityNo;
    /** 持久命令号。 */
    private String commandNo;
    /** 稳定归属。 */
    private String ownerNo;
    /** 来源门店。 */
    private String storeNo;
    /** RESERVE/PROTECT/CONFIRM/RENEW/RELEASE/EXPIRE/RECONCILE/REPLACE。 */
    private String action;
    /** 作用域内请求键SHA256。 */
    private String requestKeyHash;
    /** 冻结请求SHA256。 */
    private String payloadHash;
    /** 合法JSON冻结载荷，后续命令协议保持不可变。 */
    private String frozenPayload;
    /** 期望仲裁头版本。 */
    private Long expectedOwnerVersion;
    /** 外部来源修订。 */
    private Long sourceRevision;
    /** 可选目标持有。 */
    private String targetHoldNo;
    /** 可选目标实体版本。 */
    private Long expectedHoldVersion;
    /** 可选目标租约修订。 */
    private Long expectedLeaseRevision;
    /** ACCEPTED/SUCCEEDED/REJECTED。 */
    private String status;
    /** 合法JSON权威结果。 */
    private String resultPayload;
    /** 稳定拒绝原因。 */
    private String rejectionCode;
    /** 待恢复原因。 */
    private String waitReason;
    /** 待恢复下次处理时间。 */
    private LocalDateTime nextRetryAt;
    /** 原命令发起者。 */
    private String initiatedBy;
    /** Actual occupation association; empty for legacy booking. */
    private String commandKind;
    /** Actual occupation association; empty for legacy booking. */
    private String payloadSchema;
    /** Actual occupation association; empty for legacy booking. */
    private String executionReferenceJson;
    /** Actual occupation association; empty for legacy booking. */
    private String executionReferenceHash;
    /** Actual occupation association; empty for legacy booking. */
    private String plannedResourceCommandNo;
    /** Actual occupation association; empty for legacy booking. */
    private String plannedResourceCommandHash;
    /** Actual occupation association; empty for legacy booking. */
    private String executionPlanHash;
    /** Actual occupation association; empty for legacy booking. */
    private String originalStartCommandNo;
    /** Actual occupation association; empty for legacy booking. */
    private String actorNamespace;
    /** Actual occupation association; empty for legacy booking. */
    private String actorProvenanceHash;
    /** Actual occupation association; empty for legacy booking. */
    private String machinePurpose;
    /** Actual occupation association; empty for legacy booking. */
    private Long observationEpoch;
    /** Actual occupation association; empty for legacy booking. */
    private String originalResultNo;
    /** Actual occupation association; empty for legacy booking. */
    private Long originalResultRevision;
    /** Actual occupation association; empty for legacy booking. */
    private String originalResultHash;
    /** Actual occupation association; empty for legacy booking. */
    private String occupationOutcome;
    /** Actual occupation association; empty for legacy booking. */
    private String sealNo;
    /** Actual occupation association; empty for legacy booking. */
    private Long sealRevision;
    /** Actual occupation association; empty for legacy booking. */
    private String sealHash;
}
