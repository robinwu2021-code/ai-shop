package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 本域映射事实；公开返回VO，修订只追加。 */
@Getter
@Setter
@TableName("cim_resource_mapping_revision")
public class CimResourceMappingRevision extends BaseEntity {
    private String revisionNo;
    private String entityNo;
    private String mappingNo;
    private Long revision;
    private String sourceOwner;
    private String sourceRef;
    private String sourceRevisionRef;
    private String salesOfferRef;
    private String mappingSnapshot;
    private String contentHash;
    private LocalDateTime effectiveAt;
    private String validationReceiptRef;
    private String externalEvidenceRef;
    private String mappingOrigin;
    private String validationStatus;
    private String sourceContentHash;
    private LocalDateTime validationExpiresAt;
    private String connectionRevisionNo;
    private String authorizationNo;
    private String reason;
}
