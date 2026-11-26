package ai.neargo.shop.invoice.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** 发票明细（V1）。 */
@Getter
@Setter
@TableName("ivc_invoice_line")
public class IvcInvoiceLine {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantNo;
    private String invoiceNo;
    private Integer lineNo;
    private String sourceLineRef;
    private String name;
    private BigDecimal quantity;
    private String unit;
    private Long unitPriceMinor;
    private Long amountMinor;
    private String taxType;
    /** 创建人；不显式给就由公共字段填充器写当前操作人 */
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
    /** 更新人 */
    @TableField(fill = FieldFill.INSERT)
    private String updatedBy;
}
