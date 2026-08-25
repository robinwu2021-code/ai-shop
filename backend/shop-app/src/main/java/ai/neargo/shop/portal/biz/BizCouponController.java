package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.promotion.dto.CouponVOs.CouponIssueVO;
import ai.neargo.shop.promotion.dto.CouponVOs.CouponSaveCmd;
import ai.neargo.shop.promotion.dto.CouponVOs.CouponVO;
import ai.neargo.shop.promotion.service.CouponService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商家自己的券（P4）。
 *
 * <p><b>要 {@code biz:campaign}</b>：券是花钱的东西，与看会员（{@code biz:customer}）
 * 不是同一件事 —— 店员该能看名单，不该能发券。
 */
@Profile("api")
@RestController
public class BizCouponController {

    private final CouponService couponService;

    public BizCouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @GetMapping("/biz/coupons")
    public List<CouponVO> coupons(@RequestParam(defaultValue = "false") boolean includeEnded) {
        return couponService.list(BizContext.requireMerchantNo(), includeEnded);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @GetMapping("/biz/coupons/{couponNo}")
    public CouponVO coupon(@PathVariable String couponNo) {
        return couponService.detail(BizContext.requireMerchantNo(), couponNo);
    }

    /**
     * 建券 / 改券。<b>敞口在这一步就要算清</b>：折扣券必须封顶、
     * 发行量必须有数（定向发放除外）、预算非零时必须兜得住发行量 × 单张最大优惠。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @PostMapping("/biz/coupons")
    public CouponVO save(@RequestBody CouponSaveCmd cmd) {
        return couponService.save(BizContext.requireMerchantNo(), cmd,
                SecurityUtils.currentUserNo());
    }

    /** 暂停 / 恢复 / 结束。**不动已经发到用户手上的券** —— 那是他已有的权益 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @PutMapping("/biz/coupons/{couponNo}/status")
    public CouponVO setStatus(@PathVariable String couponNo, @RequestBody StatusReq req) {
        return couponService.setStatus(BizContext.requireMerchantNo(), couponNo, req.status());
    }

    /**
     * 按人群定向发券。
     *
     * <p>返回里的 {@code skipReasons} <b>必须显示在结果页上</b>：
     * 商家选了 37 个人、实发 25 张，只说「发放成功」的话，他会以为发出去 37 张 ——
     * 直到某个顾客说没收到。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @PostMapping("/biz/coupons/{couponNo}/issue")
    public CouponIssueVO issue(@PathVariable String couponNo, @RequestBody IssueReq req) {
        return couponService.issue(BizContext.requireMerchantNo(), couponNo, req.segmentNo(),
                SecurityUtils.currentUserNo());
    }

    /** 发放记录。留痕的消费方 —— 没有它，记了也没人看得到 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @GetMapping("/biz/coupon-issues")
    public List<CouponIssueVO> issues(@RequestParam(required = false) String couponNo) {
        return couponService.issues(BizContext.requireMerchantNo(), couponNo);
    }

    public record StatusReq(String status) {
    }

    public record IssueReq(String segmentNo) {
    }
}
