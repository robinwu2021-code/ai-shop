package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * Permanent original request identity and frozen owner instruction; amounts remain financial-owner
 * facts.
 */
@Getter
@Setter
@TableName("ord_checkout_payment_request")
public class OrdCheckoutPaymentRequest extends BaseEntity {
    private String caller;
    private String operationNo;
    private String paymentOperationNo;
    private String orderNo;
    private String subOrderNo;
    private String buyerNo;
    private String entityNo;
    private String storeNo;
    private String actorNo;
    private String actorRealm;
    private String actorKind;
    private String actorAccountNo;
    private String action;
    private String requestKeyHash;
    private String requestHash;
    private String requestJson;
    private String paymentRequestJson;
}
