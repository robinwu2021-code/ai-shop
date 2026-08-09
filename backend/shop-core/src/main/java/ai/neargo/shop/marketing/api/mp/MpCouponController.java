package ai.neargo.shop.marketing.api.mp;

import ai.neargo.shop.marketing.coupon.CouponService;
import ai.neargo.shop.marketing.coupon.dto.CouponVO;
import ai.neargo.shop.marketing.coupon.dto.UserCouponVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 优惠券（[API 清单 §2.7]）。 */
@RestController
public class MpCouponController {

    private final CouponService couponService;

    public MpCouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    /** 领券中心。游客也能看 —— 看到有券才有注册动机。 */
    @GetMapping("/mp/coupon")
    public List<CouponVO> center() {
        return couponService.center();
    }

    @PostMapping("/mp/coupon/{couponNo}/receive")
    public UserCouponVO receive(@PathVariable String couponNo) {
        return couponService.receive(couponNo);
    }

    @GetMapping("/mp/coupon/mine")
    public List<UserCouponVO> mine() {
        return couponService.mine();
    }

    @PostMapping("/mp/coupon/best")
    public CouponService.BestResult best(@RequestBody BestReq req) {
        return couponService.best(req.items() == null ? List.of() : req.items().stream()
                .map(i -> new CouponService.Item(i.goodsNo(), i.skuNo(), i.qty())).toList());
    }

    public record BestReq(List<Item> items) {
        public record Item(String goodsNo, String skuNo, int qty) {
        }
    }
}
