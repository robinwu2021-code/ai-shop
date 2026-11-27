package ai.neargo.shop.promotion.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 商家券每次发布的不可变定义，适用对象按同一版本保存在 pmt_activity_scope。 */
@Getter
@Setter
@TableName("pmt_coupon_version")
public class PmtCouponVersion extends BaseEntity {
    private String couponNo;
    private String entityNo;
    private String versionNo;
    private String ruleSnapshot;
}
