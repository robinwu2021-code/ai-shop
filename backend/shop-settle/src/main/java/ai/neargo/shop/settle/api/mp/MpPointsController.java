package ai.neargo.shop.settle.api.mp;

import ai.neargo.shop.settle.PointsService;
import ai.neargo.shop.settle.dto.PointsVOs.PointAccountVO;
import ai.neargo.shop.settle.dto.PointsVOs.PointRecordVO;
import ai.neargo.shop.settle.dto.PointsVOs.PointsDeductibleVO;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * C 端积分端点。路径与 {@code c-app/src/api/endpoints.ts} 逐条对齐。
 *
 * <p>路径用<b>复数</b> {@code /mp/points/**}：与 B 端 {@code /biz/points/**}
 * 和设计文档一致。契约里曾是单数 {@code /mp/point/**}，已随本次对齐改掉。
 */
@RestController
@RequestMapping("/mp/points")
@Validated
public class MpPointsController {

    private final PointsService pointsService;

    public MpPointsController(PointsService pointsService) {
        this.pointsService = pointsService;
    }

    /** 我的积分账户：可用与待生效**分开返回**。 */
    @GetMapping("/account")
    public PointAccountVO account(@RequestHeader("X-User-No") @NotBlank String userNo) {
        return pointsService.account(userNo);
    }

    @GetMapping("/records")
    public List<PointRecordVO> records(@RequestHeader("X-User-No") @NotBlank String userNo,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return pointsService.records(userNo, page, size);
    }

    /**
     * 结算页试算：本单最多能抵多少。
     *
     * <p><b>服务端算而不是端上算</b>：下单时服务端会再算一遍，
     * 两处算法只要有一点不同，用户就会看到「结算页说能抵 30，下单后只抵了 25」。
     */
    @GetMapping("/deductible")
    public PointsDeductibleVO deductible(@RequestHeader("X-User-No") @NotBlank String userNo,
                                         @RequestParam @NotBlank String merchantNo,
                                         @RequestParam long payableMinor) {
        return pointsService.deductible(userNo, merchantNo, payableMinor);
    }
}
