package ai.neargo.shop.portal.mp;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.promotion.dto.CouponVOs.MyCouponVO;
import ai.neargo.shop.promotion.service.CouponService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 买家券包里<b>商家发的那些</b>（新模型 {@code pmt_*}，P6）。
 *
 * <p><b>为什么是新端点而不是并进 {@code /mp/coupon/mine}</b>：老端点返回的
 * {@code UserCouponVO} 里嵌着老模型的券形状（type / faceMinor / discountRate），
 * 表达不了到店核销码、也表达不了次卡还剩几次 —— 硬塞进去会丢掉这两样，
 * 而它们正是这一批券的全部意义。两个端点并存到 P9，那时老的整个删掉。
 */
@Profile("api")
@RestController
public class MpMyCouponController {

    private final CouponService couponService;

    public MpMyCouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    /** 过期的也返回，由端上折叠 —— 券包里突然少一张，用户会认为平台吞了它 */
    @GetMapping("/mp/my-coupons")
    public List<MyCouponVO> mine() {
        return couponService.myCoupons(SecurityUtils.currentUserNo());
    }
}
