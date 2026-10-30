package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/** Durable original merchant declaration evidence; never a Pay receipt or balance. */
@Getter
@Setter
@TableName("ord_manual_service_admission_action")
public class OrdManualServiceAdmissionAction extends BaseEntity {
    private String actionNo;
    private String declarationNo;
    private String orderNo;
    private String entityNo;
    private String storeNo;
    private Long admissionRevision;
    private String action;
    private String payloadHash;
    private String resultJson;
    private String reasonCode;
}
