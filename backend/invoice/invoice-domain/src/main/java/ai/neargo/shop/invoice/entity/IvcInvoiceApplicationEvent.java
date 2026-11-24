package ai.neargo.shop.invoice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("ivc_invoice_application_event")
public class IvcInvoiceApplicationEvent {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantNo;
    private String entityNo;
    private String storeNo;
    private String applicationNo;
    private Integer revisionNo;
    private String action;
    private String fromStatus;
    private String toStatus;
    private String actorNo;
    private String reason;
    private String commandKeyHash;
    private String commandPayloadHash;
    private String relatedInvoiceNo;
    private LocalDateTime occurredAt;
}
