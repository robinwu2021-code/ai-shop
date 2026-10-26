package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** cim_operation_attempt本域原事实；凭据只存安全引用，实体不返回公开接口。 */
@Getter
@Setter
@TableName("cim_operation_attempt")
public class CimOperationAttempt extends BaseEntity {
    private String attemptRef;
    private String entityNo;
    private String operationNo;
    private Long attemptNo;
    private Long fence;
    private String attemptKind;
    private String providerRequestRef;
    private String providerTaskRef;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String outcome;
    private String responseRef;
    private String errorCode;
    private String lateEvidenceRef;
    private String lateOutcome;
}
