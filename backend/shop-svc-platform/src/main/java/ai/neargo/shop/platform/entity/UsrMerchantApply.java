package ai.neargo.shop.platform.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 入驻申请。与 {@code usr_merchant} 分开：申请可以被驳回后重提，
 * 而商家主体一旦创建就有了商品、订单、结算，不该跟着申请状态来回变。
 */
@Getter
@Setter
@TableName("usr_merchant_apply")
public class UsrMerchantApply extends BaseEntity {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    private String applyNo;
    private String userNo;

    /** 审核通过后回填。 */
    private String merchantNo;

    private String name;
    private String merchantType;
    private String contactPhone;
    private String qualifications;
    private String status;
    private String rejectReason;
    private String auditedBy;
    private Long auditedAt;
}
