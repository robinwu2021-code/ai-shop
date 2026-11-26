package ai.neargo.shop.invoice.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 买家常用载具（V3）。平台级、跨主体通用：手机条码是财政部发给个人的，不属于某个商家。 */
@Getter
@Setter
@TableName("ivc_user_carrier")
public class IvcUserCarrier {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String carrierNo;
    private String tenantNo;
    private String userNo;
    /** 3J0002 手机条码 / CQ0001 自然人凭证 */
    private String carrierType;
    private String carrierId;
    private String label;
    // 新库的非空列不带默认值（旧表有）：初值写在实体上，插入时由代码给出。
    private Boolean isDefault = false;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 创建人；不显式给就由公共字段填充器写当前操作人 */
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
    /** 更新人 */
    @TableField(fill = FieldFill.INSERT)
    private String updatedBy;
}
