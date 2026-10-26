package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** cim_authorization_revision本域原事实；凭据只存安全引用，实体不返回公开接口。 */
@Getter
@Setter
@TableName("cim_authorization_revision")
public class CimAuthorizationRevision extends BaseEntity {
    private String authorizationNo;
    private String entityNo;
    private String accountNo;
    private Long revisionNo;
    private String credentialRef;
    private String grantedScopes;
    private String capabilityHash;
    private LocalDateTime authorizedAt;
    private LocalDateTime expiresAt;
    private String status;
    private String evidenceRef;
}
