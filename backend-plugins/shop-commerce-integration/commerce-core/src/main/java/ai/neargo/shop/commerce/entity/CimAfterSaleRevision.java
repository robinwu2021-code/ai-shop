package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("cim_after_sale_revision")
public class CimAfterSaleRevision extends BaseEntity {
    private String revisionNo;
    private String entityNo;
    private String caseNo;
    private String externalRevision;
    private String contentHash;
    private String payloadJson;
    private String originalReceiptRef;
    private String originEventNo;
    private Long externalSequence;
}
