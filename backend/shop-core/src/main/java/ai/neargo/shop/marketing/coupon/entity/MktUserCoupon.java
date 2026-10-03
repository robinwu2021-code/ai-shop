package ai.neargo.shop.marketing.coupon.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 用户券。 */
@Getter
@Setter
@TableName("mkt_user_coupon")
public class MktUserCoupon extends BaseEntity {

    public static final String UNUSED = "UNUSED";
    public static final String USED = "USED";
    public static final String EXPIRED = "EXPIRED";

    private String userCouponNo;
    private String couponNo;
    private String userNo;
    private String status;

    /** 用在哪一单（主单）。取消订单时按它退回。 */
    private String orderNo;

    private Long receivedAt;
    private Long usedAt;
}
