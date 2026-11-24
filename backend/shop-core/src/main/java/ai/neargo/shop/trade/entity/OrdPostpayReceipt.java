package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 行业后付人工实收：一张结算单一行（TDD-餐饮柜台后付结账 · ADR-059）。 */
@Getter
@Setter
@TableName("ord_postpay_receipt")
public class OrdPostpayReceipt extends BaseEntity {
    public static final String PROVENANCE = "POSTPAY_RECEIPT";

    private String receiptNo;
    private String entityNo;
    private String storeNo;
    private String bizNo;
    private String currency;
    private Long dueMinor;
    private Long receivedMinor;
    private Long changeMinor;
    private String requestKeyHash;
    private String payloadHash;
    private String actorNo;
    private String receiptProvenance;
    private LocalDateTime receivedAt;
    private LocalDateTime recordedAt;
}
