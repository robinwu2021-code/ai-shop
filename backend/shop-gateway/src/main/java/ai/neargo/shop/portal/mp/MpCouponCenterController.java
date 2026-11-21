package ai.neargo.shop.portal.mp;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.paybridge.CouponBestAppService;
import ai.neargo.shop.paybridge.LegacyCouponWalletProjectionService;
import org.springframework.beans.factory.annotation.Autowired;
import ai.neargo.shop.marketing.coupon.CouponService;
import ai.neargo.shop.marketing.coupon.dto.CouponVO;
import ai.neargo.shop.marketing.coupon.dto.UserCouponVO;
import ai.neargo.shop.promotion.dto.CouponVOs.CustomerCoupon;
import ai.neargo.shop.promotion.dto.CouponVOs.HeldCoupon;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * C 端领券：领券中心、领券、我的券、最优券试算（[API 清单 §2.7]）。
 *
 * <p><b>新老两套券在这里合流</b>（优惠券全链路梳理 批 1，2026-09-21）。
 * 此前这四个端点在老 marketing 包里，只读 {@code mkt_coupon} —— 而商家在 B 端建的券
 * 写的是新模型 {@code pmt_coupon}，于是<b>商家建的券顾客一张都看不到、领不到</b>。
 * 下单那一侧早已两套都能用（{@code CouponPortRouter} 按券归属分流），只差领券这一侧。
 *
 * <p>合流放在 portal 而不是任何一个域里：两个域互不依赖（ArchitectureTest），
 * 组合它们是 portal 的事，与 CouponPortRouter 同一个位置。
 * <b>返回形状沿用老契约</b>（{@code CouponVO / UserCouponVO / BestResult}），端上不用改。
 */
@Profile("api")
@RestController
public class MpCouponCenterController {

    @Autowired private LegacyCouponWalletProjectionService wallet;
    @Autowired private CouponBestAppService best;
    private final CouponService legacy;
    private final ai.neargo.shop.promotion.service.CouponService promo;
    private final GoodsQueryPort goodsPort;
    private final MerchantQueryPort merchantPort;

    public MpCouponCenterController(CouponService legacy,
                                    ai.neargo.shop.promotion.service.CouponService promo,
                                    GoodsQueryPort goodsPort, MerchantQueryPort merchantPort) {
        this.legacy = legacy;
        this.promo = promo;
        this.goodsPort = goodsPort;
        this.merchantPort = merchantPort;
    }

    /** 领券中心。游客也能看 —— 看到有券才有注册动机。 */
    @GetMapping("/mp/coupon")
    public List<CouponVO> center() {
        List<CouponVO> out = new ArrayList<>(legacy.center());
        String userNo = SecurityUtils.currentUserNoOrNull();
        for (CustomerCoupon c : promo.center(userNo)) {
            out.add(toVO(c, c.startAt(), c.endAt()));
        }
        return out;
    }

    /** 领一张。按券模板属于哪一套分流 —— 两套的库存计数器各自独立，不能混着扣 */
    @PostMapping("/mp/coupon/{couponNo}/receive")
    public UserCouponVO receive(@PathVariable String couponNo) {
        if (promo.ownsTemplate(couponNo)) {
            return toVO(promo.receive(SecurityUtils.currentUserNo(), couponNo));
        }
        return legacy.receive(couponNo);
    }

    /** 下单可抵扣的券（两套合并）。到店核销的券不在这里 —— 它们在 /mp/my-coupons */
    @GetMapping("/mp/coupon/mine")
    public List<UserCouponVO> mine() {
        List<UserCouponVO> out = new ArrayList<>(legacy.mine());
        for (HeldCoupon h : promo.held(SecurityUtils.currentUserNo())) {
            out.add(toVO(h));
        }
        return wallet.checkoutWallet(SecurityUtils.currentUserNo(),out);
    }

    /**
     * 最优券试算：老模型那份原样拿来，再把新模型的券按同一套口径评一遍合进去。
     *
     * <p>口径与下单一致：商家券只对本店金额计门槛（{@code CouponAllocServiceImpl}），
     * 算优惠用 {@link PmtCoupon#discountFor} —— 两处各算一次的后果是试算说能减、下单不减。
     */
    @PostMapping("/mp/coupon/best")
    public CouponService.BestResult best(@RequestBody BestReq req) {
        List<CouponService.Item> items = req.items() == null ? List.of() : req.items().stream()
                .map(i -> new CouponService.Item(i.goodsNo(), i.skuNo(), i.qty())).toList();
        return best.best(SecurityUtils.currentUserNo(),items,mine());
    }

    public record BestReq(List<Item> items) {
        public record Item(String goodsNo, String skuNo, int qty) {
        }
    }

    // ------------------------------------------------------------------ 新模型 → 老契约形状

    private UserCouponVO toVO(HeldCoupon h) {
        return new UserCouponVO(h.userCouponNo(), toVO(h.coupon(), h.receivedAt(), h.expireAt()),
                h.status(), h.usableNow(), h.receivedAt(), h.usedAt());
    }

    /**
     * 新券映射成 C 端的 {@code Coupon}：现金券 = 满减（面额、门槛直接对应），
     * 折扣券 = 折扣（万分比单位两边相同，封顶对应 maxDiscount）。
     * 有效期：模板上看窗口；手里那张看领取时算好的到期时刻。
     */
    private CouponVO toVO(CustomerCoupon c, long startAt, long endAt) {
        boolean percent = PmtCoupon.PERCENT.equals(c.benefitMode());
        long end = endAt > 0 ? endAt
                : c.validDays() == null ? 0L : System.currentTimeMillis() + c.validDays() * 86_400_000L;
        return new CouponVO(c.couponNo(), c.title(), percent ? "DISCOUNT" : "FULL_CUT",
                percent ? 0L : c.benefitValue(), percent ? (int) c.benefitValue() : 0,
                c.minAmountMinor(), c.capMinor(),
                c.funder() == null ? PmtCoupon.BY_MERCHANT : c.funder(), c.entityNo(),
                startAt, end, c.remain(), c.received(), c.status(), scopeDescOf(c.entityNo()));
    }

    private String scopeDescOf(String entityNo) {
        if (entityNo == null || entityNo.isBlank() || "PLATFORM".equals(entityNo)) {
            return "全平台可用";
        }
        return "仅限" + merchantPort.find(entityNo).map(MerchantQueryPort.MerchantBrief::merchantName)
                .orElse(entityNo);
    }
}
