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
         * **预算与张数在同一条 UPDATE 里判定**，理由与上面那句「先查后改在并发下
         * 必然超发」完全一样：分两步的话，两个人同时领最后一份预算都会通过。
         * 而超预算的后果是真金白银，比多发一张券重。
         *
         * budget_minor = 0 表示不限（存量券全是这样，迁移不改变已有行为）。
         *
         * ⚠️ **折扣券挡不住**：face_minor 为 0，(received_count+1)*0 恒 ≤ 预算。
         * 折扣券的实际支出取决于用券那一单的金额，**发放时算不出来** ——
         * 与其按面额估一个假数去挡，不如明说这里挡不住，让预算这件事
         * 在折扣券上走核销侧（那是另一件事）。
         */
        @Update("""
                UPDATE mkt_coupon SET received_count = received_count + 1, version = version + 1
                WHERE coupon_no = #{couponNo} AND deleted = 0
                  AND (total_count = 0 OR received_count < total_count)
                  AND (budget_minor = 0
                       OR (received_count + 1) * face_minor <= budget_minor)
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
}
