package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** cim_operation本域原事实；凭据只存安全引用，实体不返回公开接口。 */
@Getter
@Setter
@TableName("cim_operation")
public class CimOperation extends BaseEntity {
    private String operationNo;
    private String entityNo;
    private String storeNo;
    private String connectionNo;
    private String destination;
    private String operationType;
    private String businessRef;
    private String businessRevision;
    private String semanticKey;
    private String requestHash;
    private String requestRef;
    private String status;
    private Long currentAttemptNo;
    private Long fence;
    private LocalDateTime leaseUntil;
    private LocalDateTime nextRecoveryAt;
    private String originalEvidenceRef;
    private String accountNo;
    private String profileNo;
    private String targetScopeKind;
    private String targetScopeKey;
    private String callbackStateHash;
    private LocalDateTime expiresAt;
    private LocalDateTime callbackConsumedAt;
    private String callbackRequestHash;
    private String authorizationUrl;
    private String returnTargetCode;
    private String reasonCode;
    private String reason;
}
