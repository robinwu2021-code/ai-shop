package ai.neargo.shop.marketing.coupon;

import ai.neargo.shop.marketing.coupon.dto.CouponVO;
import ai.neargo.shop.marketing.coupon.dto.UserCouponVO;

import java.util.List;

/** 优惠券（[API 清单 §2.7]）。领券中心游客可看，领取与券包需要登录。 */
public interface CouponService {

    /** 领券中心：当前可领的券。 */
    List<CouponVO> center();

    UserCouponVO receive(String couponNo);

    List<UserCouponVO> mine();

    /**
     * 最优券试算。**不可用的券也返回并给出原因** ——
     * 「为什么我的券用不了」是券功能最大的客诉来源。
     */
    BestResult best(List<Item> items);

    record Item(String goodsNo, String skuNo, int qty) {
    }

    record BestResult(String bestUserCouponNo, long discountMinor,
                      List<UserCouponVO> usable, List<Unusable> unusable) {

        public record Unusable(String userCouponNo, String reason) {
        }
    }
}
