package ai.neargo.shop.invoice.entity;

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
    private Boolean isDefault;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
