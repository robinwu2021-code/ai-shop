package ai.neargo.shop.marketing.coupon.mapper;

import ai.neargo.shop.marketing.coupon.entity.MktCoupon;
import ai.neargo.shop.marketing.coupon.entity.MktUserCoupon;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 券域的 Mapper 集合。 */
public final class CouponMappers {

    private CouponMappers() {
    }

    public interface CouponMapper extends BaseMapper<MktCoupon> {

        /**
         * 原子领取：库存判断写在 WHERE 里，靠影响行数判定成功与否。
         * 先查后改在并发下必然超发 —— 两个请求都查到「还剩 1 张」。
         *
         * @return 1=领取成功，0=已领完
         */
        @Update("""
                UPDATE mkt_coupon SET received_count = received_count + 1, version = version + 1
                WHERE coupon_no = #{couponNo} AND deleted = 0
                  AND (total_count = 0 OR received_count < total_count)
                """)
        int tryReceive(@Param("couponNo") String couponNo);
    }

    public interface UserCouponMapper extends BaseMapper<MktUserCoupon> {
    }
}
