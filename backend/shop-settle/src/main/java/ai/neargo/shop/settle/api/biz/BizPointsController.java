package ai.neargo.shop.settle.api.biz;

import ai.neargo.shop.settle.PointsService;
import ai.neargo.shop.settle.dto.PointsVOs.MerchantPointAccountVO;
import ai.neargo.shop.settle.dto.PointsVOs.MerchantPointsRecordVO;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * B 端积分端点。
 *
 * <p><b>商家不感知积分抵扣</b>（V34）：他收到的是订单全额减各项费用。
 * 所以这里只有他自己发分的成本与开关 —— 没有「兑付进账」「账期单」这些概念，
 * 那两样随 {@code pts_merchant_ledger} / {@code stl_points_bill} 一起废除了。
 */
@RestController
@RequestMapping("/biz/points")
@Validated
public class BizPointsController {

    private final PointsService pointsService;

    public BizPointsController(PointsService pointsService) {
        this.pointsService = pointsService;
    }

    /** 本期发分服务费与开关状态。 */
    @GetMapping("/account")
    public MerchantPointAccountVO account(@RequestHeader("X-Merchant-No") @NotBlank String merchantNo) {
        return pointsService.merchantAccount(merchantNo);
    }

    /** 发分服务费明细：一单一条，来自 {@code stl_bill.points_fee_minor}。 */
    @GetMapping("/records")
    public List<MerchantPointsRecordVO> records(
            @RequestHeader("X-Merchant-No") @NotBlank String merchantNo,
            @RequestParam(required = false) String period,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return pointsService.merchantRecords(merchantNo, period, page, size);
    }

    /**
     * 开/关本店积分。
     *
     * <p><b>关闭只影响将来</b>：已发出的分仍有效、已扣的服务费不退 ——
     * 否则关一次开关就是一次资金事故。
     */
    @PostMapping("/toggle")
    public MerchantPointAccountVO toggle(@RequestHeader("X-Merchant-No") @NotBlank String merchantNo,
                                         @RequestBody ToggleReq req) {
        return pointsService.toggleMerchant(merchantNo, req.enabled());
    }

    public record ToggleReq(Boolean enabled) {
    }
}
