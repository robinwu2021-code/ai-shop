package ai.neargo.shop.invoice.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 门店开票设定（V15）：自订行默认课税别与开票时机。一店一行，没有行 = 无默认、手动开。 */
@Getter
@Setter
@TableName("ivc_store_setting")
public class IvcStoreSetting {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantNo;
    private String entityNo;
    private String storeNo;
    /** TAXABLE / ZERO_RATED / EXEMPT；null = 不带默认 */
    private String defaultTaxType;
    /** ON_PAID / MANUAL / LATER */
    private String invoiceTiming;
    @TableField(fill = FieldFill.INSERT)
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 创建人；不显式给就由公共字段填充器写当前操作人 */
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
}
