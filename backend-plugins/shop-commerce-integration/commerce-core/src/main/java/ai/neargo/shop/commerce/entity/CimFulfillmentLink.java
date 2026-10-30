package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * Original committed fulfillment references; supplier acknowledgement is independent of local
 * business state.
 */
@Getter
@Setter
@TableName("cim_fulfillment_link")
public class CimFulfillmentLink extends BaseEntity {
    private String linkNo;
    private String entityNo;
    private String channelOrderNo;
    private String sourceOwner;
    private String sourceFulfillmentRef;
    private String sourceRevision;
    private String fulfillmentType;
    private String stage;
    private String externalFulfillmentRef;
    private String trackingEvidenceRef;
    private String operationNo;
    private String status;
    private String sourceHash;
    private String sourceSnapshot;
    private String payloadJson;
    private String connectionRevisionNo;
}
