package ai.neargo.shop.invoice.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("ivc_invoice_application")
public class IvcInvoiceApplication {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantNo;
    private String entityNo;
    private String storeNo;
    private String applicationNo;
    private String sourceType;
    private String sourceNo;
    private Integer currentRevision;
    private String status;
    private String issuedInvoiceNo;
    private LocalDateTime issuedAt;
    /** 最近一次驳回的原因；重新提交后清空 */
    private String rejectReason;
    /** 最近一次改变状态的操作人 */
    private String operatorNo;
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableField(fill = FieldFill.INSERT)
    private String updatedBy;
}
