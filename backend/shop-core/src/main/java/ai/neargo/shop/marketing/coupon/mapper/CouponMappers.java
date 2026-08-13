package ai.neargo.shop.marketing.coupon.mapper;

import ai.neargo.shop.marketing.coupon.entity.MktCoupon;
import ai.neargo.shop.marketing.coupon.entity.MktUserCoupon;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
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
        /*
         * **预算不在这条 UPDATE 里判了**（TDD-营销预算前置）。
         *
         * 此前这里有一条 `(received_count+1)*face_minor <= budget_minor`：
         * 运行时才发现要不要拦。现在 `CouponServiceImpl.saveCoupon` 建券/改券时
         * 已经把「预算 ≥ 发行量 × 单张最大优惠」钉死为前置断言 ——
         * 张数闸（下面这条）本身就保证了 `已领张数 × 面额 ≤ 发行量 × 面额 ≤ 预算`，
         * 这条判断因此**恒真**，留着只是多一次无意义的比较。
         *
         * 折扣券这条更彻底：它的 face_minor 恒为 0，`(received_count+1)*0` 恒 ≤
         * 任何预算，这条判断从来没有真正拦住过一张折扣券 —— 折扣券的敞口现在
         * 由建券时的 `totalCount × maxDiscountMinor` 校验兜底，不再指望这里。
         */
        @Update("""
                UPDATE mkt_coupon SET received_count = received_count + 1, version = version + 1
                WHERE coupon_no = #{couponNo} AND deleted = 0
                  AND (total_count = 0 OR received_count < total_count)
                """)
        int tryReceive(@Param("couponNo") String couponNo);

        /** 某张券模板已核销的张数。运营看效果看的是这个，不是领取数 */
        @Select("""
                SELECT COUNT(*) FROM mkt_user_coupon
                WHERE coupon_no = #{couponNo} AND status = 'USED' AND deleted = 0
                """)
        int redeemedCount(@Param("couponNo") String couponNo);
    }

    public interface UserCouponMapper extends BaseMapper<MktUserCoupon> {
    }

    public interface CouponIssueMapper
            extends BaseMapper<ai.neargo.shop.marketing.coupon.entity.MktCouponIssue> {
    }
}
