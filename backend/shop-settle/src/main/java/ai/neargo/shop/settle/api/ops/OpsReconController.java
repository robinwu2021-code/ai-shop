package ai.neargo.shop.settle.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.settle.service.ReconService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.auth.SecurityUtils;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 对账差异（P-4.2.1）。
 *
 * <p>补的是资金侧最后一个盲区：{@code stl_payment.reconciled_at} 从建库起就写着
 * 「掉单只能靠对账发现，没有别的手段」，而在此之前<b>没有任何代码写过它</b>。
 *
 * <p><b>列表带覆盖范围说明。</b> 一期只有平台侧自查这一个产出方，
 * 「渠道扣了钱而我方没有记录」那一类现在看不见 —— 不把这句话下发给端上的话，
 * 空列表会被读成「今天账是平的」，而那是句假话。
 */
@Profile("ops")
@RestController
@Validated
public class OpsReconController {

    private final ReconService reconService;
    private final AuditLogPort auditLogPort;

    public OpsReconController(ReconService reconService, AuditLogPort auditLogPort) {
        this.reconService = reconService;
        this.auditLogPort = auditLogPort;
    }

    /** 差异列表。默认给待处置的 —— 这是个队列，历史是次要视图 */
    @GetMapping("/ops/payments/recon-diffs")
    @PreAuthorize("@perm.can('" + Perms.SETTLE_MANAGE + "')")
    public PageData<ReconService.ReconDiffVO> diffs(@RequestParam(required = false) String status,
                                                    @RequestParam(defaultValue = "1") long page,
                                                    @RequestParam(defaultValue = "20") long size) {
        return PageData.ofAll(reconService.diffs(status), page, size);
    }

    /**
     * 本列表覆盖到哪些差异 —— 端上照它显示提示条。
     *
     * <p>单独一个端点而不是塞进列表响应：列表是分页包，
     * 把说明挂在分页包上，翻到第二页时它就没了。
     */
    @GetMapping("/ops/payments/recon-coverage")
    @PreAuthorize("@perm.can('" + Perms.SETTLE_MANAGE + "')")
    public ReconService.Coverage coverage() {
        return reconService.coverage();
    }

    /** 已处置。结论必填，且原样留在单据上 */
    @PostMapping("/ops/payments/recon-diffs/{diffNo}/resolve")
    @PreAuthorize("@perm.can('" + Perms.SETTLE_MANAGE + "')")
    public ReconService.ReconDiffVO resolve(@PathVariable String diffNo,
                                            @RequestBody DecideReq req) {
        var vo = reconService.decide(diffNo, false, req.resolution(), SecurityUtils.currentUserNo());
        // 钱的事必须能追到是谁在什么时候下的结论
        auditLogPort.record("RECON_RESOLVE", diffNo, req.resolution());
        return vo;
    }

    /** 忽略：认定不是问题。<b>同样必须写理由</b> —— 下个月再对账时没人记得为什么放过它 */
    @PostMapping("/ops/payments/recon-diffs/{diffNo}/ignore")
    @PreAuthorize("@perm.can('" + Perms.SETTLE_MANAGE + "')")
    public ReconService.ReconDiffVO ignore(@PathVariable String diffNo,
                                           @RequestBody DecideReq req) {
        var vo = reconService.decide(diffNo, true, req.resolution(), SecurityUtils.currentUserNo());
        auditLogPort.record("RECON_IGNORE", diffNo, req.resolution());
        return vo;
    }

    public record DecideReq(String resolution) {
    }
}
