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

    // ---------------------------------------------------------------- 平台侧（P-7.1）

    /**
     * 平台券列表。**跨商家**——平台要看到所有券，包括商家自己发的。
     *
     * @param status 为空给全部
     */
    List<CouponVO> opsCoupons(String status);

    /**
     * 平台改券状态：{@code ACTIVE} ⇄ {@code PAUSED}，或置 {@code ENDED}。
     *
     * <p>这是**出事时的止损手段**：商家发了一张面额超过商品价的券、或者门槛写成 0
     * 导致人人可领，此前平台只能去改数据库。
     *
     * <p>只改「还能不能领」，**不动已领到手的券**——那是用户已有的权益。
     *
     * @param reason 必填，写进审计。停别人的券要说得出为什么
     */
    CouponVO setCouponStatus(String couponNo, String status, String reason, String operatorNo);
}
