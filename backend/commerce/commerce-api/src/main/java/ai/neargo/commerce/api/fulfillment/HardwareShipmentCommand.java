package ai.neargo.commerce.api.fulfillment;

import ai.neargo.commerce.api.common.*;

public record HardwareShipmentCommand(String tenantNo, String orderNo, String orderComponentNo, String operationNo, String carrierCode, String trackingNo, long quantity, String operatorNo) {
    public HardwareShipmentCommand(String tenantNo,String orderNo,String orderComponentNo,String operationNo,String carrierCode,String trackingNo,long quantity) {
        this(tenantNo,orderNo,orderComponentNo,operationNo,carrierCode,trackingNo,quantity,null);
    }
}
