package ai.neargo.shop.promotion.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 发到某个人手上的<b>那一张</b>。
 *
 * <p><b>有效期落在这一行上</b>：领取后 N 天有效的券，领的那一刻就把 {@code expireAt}
 * 算好落库。现算的话，商家改一次模板的天数，会把<b>已经发出去的所有券</b>一起改掉 ——
 * 用户手上那张昨天还能用的券今天过期了，而没有任何记录说明发生过什么。
 */
@Getter
@Setter
@TableName("pmt_user_coupon")
public class PmtUserCoupon extends BaseEntity {

    public static final String UNUSED = "UNUSED";
    /** 一次性券用掉了，或次卡用满了 */
    public static final String USED = "USED";
    public static final String EXPIRED = "EXPIRED";
    /** 平台/商家收回。留状态不删行 —— 收回过什么要能查 */
    public static final String REVOKED = "REVOKED";

    private String userCouponNo;
    private String couponNo;
    private String userNo;
    private String entityNo;
    private String issueNo;
    private String status;
    private Integer timesUsed;
    private String orderNo;
    private Long usedAt;
    private Long receivedAt;
    private Long expireAt;
    /** 到店核销码。只有 {@code STORE_CODE} 券有 */
    private String redeemCode;

    /** 还能不能用：没用完、没过期、没被收回 */
    public boolean usableAt(long now, int timesTotal) {
        return UNUSED.equals(status)
                && (timesUsed == null ? 0 : timesUsed) < Math.max(timesTotal, 1)
                && (expireAt == null || expireAt >= now);
    }
}
