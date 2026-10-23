package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Source-to-original-order binding metadata; monetary truth remains in the original order and quote tables. */
@Getter
@Setter
@TableName("ord_industry_order_source")
public class OrdIndustryOrderSource extends BaseEntity {
    private String orderNo;
    private String subOrderNo;
    private String entityNo;
    private String storeNo;
    private String buyerNo;
    private String actorNo;
    private String sourceSystem;
    private String sourceType;
    private String sourceNo;
    private Long sourceVersion;
    private String sourceHash;
    private String sourceSubjectNo;
    private String quoteNo;
    private String confirmationDigest;
    private String requestHash;
    private String orderMode;
    private String status;
    private String allocationsJson;
    private String economicsHash;
    private String paymentOutTradeNo;
    private String paymentChannel;
    private String paymentState;
    private String paymentRequestJson;
    private String payParamsJson;
    private Long paramsExpiresAt;
    /** Independent fair query-only recovery lease; never a payment dispatch authorization. */
    private Long nextRecoveryAt;
    private String lastRecoveryCode;
}
