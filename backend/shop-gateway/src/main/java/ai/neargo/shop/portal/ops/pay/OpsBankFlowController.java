package ai.neargo.shop.portal.ops.pay;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.payclient.OpsBankFlowAppService;
import ai.neargo.shop.payclient.OpsBankFlowAppService.ImportResultVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 银行流水导入（TDD-供应商结算与双轨资金 §10）。
 *
 * <p><b>为什么是 CSV 文本而不是 multipart</b>：解析必须在服务端 ——
 * 判据不能放在浏览器里，而且银企直连接上时换的是取数那一段、解析器一行不动。
 * 一旦解析在服务端，multipart 只多了文件大小、临时文件、编码嗅探三件事，
 * 换不到任何东西。前端读文件为文本（非 UTF-8 导出用 {@code TextDecoder('gbk')} 兜底）后贴上来。
 *
 * <p>权限判 {@link Perms#FINANCE_PAYOUT_EXECUTE} —— 与付款清单导出、登记已付同一个码：
 * 上传流水是出款岗工作的延续，不是新角色。
 */
@Profile("ops")
@RestController
@Validated
public class OpsBankFlowController {

    private final OpsBankFlowAppService app;

    public OpsBankFlowController(OpsBankFlowAppService app) {
        this.app = app;
    }

    public record ImportCmd(String fileName, @NotBlank String csv) {
    }

    /**
     * <p><b>写 critical 审计</b>（与付款清单导出对称）：银行流水是对账的判据，
     * <b>判据从哪来必须留痕</b>。审计里记文件名与三个计数，
     * <b>不记流水内容</b> —— 对方户名与账号进审计与进日志是同一类泄露。
     */
    @PostMapping("/ops/payables/bank-flows/import")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_PAYOUT_EXECUTE + "')")
    public ImportResultVO importFlows(@Valid @RequestBody ImportCmd cmd) {
        // 审计写在 service 里，与 imported_by 同一处 —— 见那边的注释
        return app.importCsv(cmd.fileName(), cmd.csv());
    }
}
