package ai.neargo.shop.portal.mp.pay;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.pay.PointsService;
import ai.neargo.shop.pay.dto.PointsVOs.PointAccountVO;
import ai.neargo.shop.pay.dto.PointsVOs.PointRecordVO;
import ai.neargo.shop.pay.dto.PointsVOs.PointsDeductibleVO;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * C 端积分端点。路径与 {@code c-app/src/api/endpoints.ts} 逐条对齐。
 *
 * <p>路径用<b>复数</b> {@code /mp/points/**}：与 B 端 {@code /biz/points/**}
 * 和设计文档一致。契约里曾是单数 {@code /mp/point/**}，已随本次对齐改掉。
 *
 * <p><b>用户号从鉴权上下文取，不收 {@code X-User-No} 头</b> ——
 * 与 {@code BizPointsController} 里那条「商家号从 BizContext 取」是同一条规矩。
 *
 * <p>这三个端点此前<b>真的收这个头</b>，而 {@code /mp/**} 在安全层是 permitAll
 * （游客要能逛商品），两者叠在一起的后果是：<b>任何人不用登录，
 * 把别人的 userNo 填进请求头就能读到他的积分余额与消费流水</b>
 * （流水里带子单号、商家、金额）。
 *
 * <p>同时它从来没工作过 —— c-app 根本不发这个头，真实前端调过来是 400。
 * 一个越权漏洞和一条断掉的链路，恰好长在同一行代码上。
 */
@Profile("api")
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
    public PointAccountVO account() {
        return pointsService.account(SecurityUtils.requireUser().userNo());
    }

    @GetMapping("/records")
    public List<PointRecordVO> records(@RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return pointsService.records(SecurityUtils.requireUser().userNo(), page, size);
    }

    /**
     * 结算页试算：本单最多能抵多少。
     *
     * <p><b>服务端算而不是端上算</b>：下单时服务端会再算一遍，
     * 两处算法只要有一点不同，用户就会看到「结算页说能抵 30，下单后只抵了 25」。
     */
    @GetMapping("/deductible")
    public PointsDeductibleVO deductible(
            @RequestParam @NotBlank String merchantNo,
            @RequestParam long payableMinor,
            @RequestParam(required = false) String payMode,
            @RequestHeader(value = "X-Client", required = false) String client) {
        return pointsService.deductible(SecurityUtils.requireUser().userNo(),
                merchantNo, payableMinor, payMode, client);
    }
}
