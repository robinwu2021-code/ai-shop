package ai.neargo.shop.portal.biz.pay;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import org.springframework.security.access.prepost.PreAuthorize;
import ai.neargo.shop.pay.PointsService;
import ai.neargo.shop.pay.dto.PointsVOs.MerchantPointAccountVO;
import ai.neargo.shop.pay.dto.PointsVOs.MerchantPointsRecordVO;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * B 端积分端点。
 *
 * <p><b>商家不感知积分抵扣</b>（V34）：他收到的是订单全额减各项费用。
 * 所以这里只有他自己发分的成本与开关 —— 没有「兑付进账」「账期单」这些概念，
 * 那两样随 {@code pts_merchant_ledger} / {@code stl_points_bill} 一起废除了。
 *
 * <p><b>商家号从 {@link BizContext} 取，不收 {@code X-Merchant-No} 头</b>。
 * 这三个端点此前是全 B 端唯一让客户端自报商家号的 —— 那等于
 * 「传谁的号就查谁的账」，改一个请求头就能看别家店的积分成本、甚至关掉他家的积分开关。
 * 另外 66 个端点都从上下文取，只有这里不是；做角色判权盘点时撞出来的。
 */
@Profile("api")
@RestController
@RequestMapping("/biz/points")
@Validated
public class BizPointsController {

    private final PointsService pointsService;

    public BizPointsController(PointsService pointsService) {
        this.pointsService = pointsService;
    }

    /** 本期发分服务费与开关状态。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
    @GetMapping("/account")
    public MerchantPointAccountVO account() {
        return pointsService.merchantAccount(BizContext.requireMerchantNo());
    }

    /** 发分服务费明细：一单一条，来自 {@code stl_bill.points_fee_minor}。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
    @GetMapping("/records")
    public List<MerchantPointsRecordVO> records(
            @RequestParam(required = false) String period,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return pointsService.merchantRecords(BizContext.requireMerchantNo(), period, page, size);
    }

    /**
     * 开/关本店积分。
     *
     * <p><b>关闭只影响将来</b>：已发出的分仍有效、已扣的服务费不退 ——
     * 否则关一次开关就是一次资金事故。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
    @PostMapping("/toggle")
    public MerchantPointAccountVO toggle(@RequestBody ToggleReq req) {
        return pointsService.toggleMerchant(BizContext.requireMerchantNo(), req.enabled());
    }

    public record ToggleReq(Boolean enabled) {
    }
}
