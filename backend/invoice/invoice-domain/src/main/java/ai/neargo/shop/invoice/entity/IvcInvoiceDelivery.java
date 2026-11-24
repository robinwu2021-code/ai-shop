package ai.neargo.shop.invoice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("ivc_invoice_delivery")
public class IvcInvoiceDelivery {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantNo;
    private String entityNo;
    private String storeNo;
    private String deliveryNo;
    private String invoiceNo;
    private String applicationNo;
    private Integer applicationRevisionNo;
    private String exportNo;
    private String parentDeliveryNo;
    private Long recipientSecretRef;
    private String recipientMask;
    private String status;
    private String reason;
    private String actorNo;
    private String idempotencyHash;
    private String requestHash;
    private String templateVersion;
    private String contentHash;
    private String providerMessageId;
    private String failureCode;
    private String tokenHash;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime openedAt;
}
