package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 本域映射事实；公开返回VO，修订只追加。 */
@Getter
@Setter
@TableName("cim_resource_mapping")
public class CimResourceMapping extends BaseEntity {
    private String mappingNo;
    private String entityNo;
    private String storeNo;
    private String connectionNo;
    private String objectType;
    private String externalRef;
    private String externalIdentityHash;
    private String externalIdentityJson;
    private String activeRevisionNo;
    private String latestRevisionNo;
    private String verificationOperationNo;
    private LocalDateTime verificationLeaseUntil;
    private LocalDateTime nextVerificationAt;
}
