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
@TableName("ivc_invoice_application_revision")
public class IvcInvoiceApplicationRevision {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantNo;
    private String applicationNo;
    private Integer revisionNo;
    private String buyerTaxId;
    private String buyerName;
    private String carrierType;
    private String carrierId;
    private String donateCode;
    private String currency;
    private Long sourceAmountMinor;
    private String sourceFingerprint;
    private String sourceSnapshot;
    private String submittedBy;
    private LocalDateTime submittedAt;
    /** 创建人；不显式给就由公共字段填充器写当前操作人 */
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
    /** 更新人 */
    @TableField(fill = FieldFill.INSERT)
    private String updatedBy;
}
