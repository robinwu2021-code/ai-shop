package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("cim_availability_publication")
public class CimAvailabilityPublication extends BaseEntity {
    private String publicationNo;
    private String availabilityKind;
    private LocalDateTime nextSourceCheckAt;
    private Long generation;
    private String targetScopeKey;
    private String entityNo;
    private String storeNo;
    private String connectionNo;
    private String mappingNo;
    private String mappingRevisionNo;
    private String sourceHash;
    private String payloadJson;
    private String operationNo;
    private String status;
    private String externalEvidenceRef;
}
