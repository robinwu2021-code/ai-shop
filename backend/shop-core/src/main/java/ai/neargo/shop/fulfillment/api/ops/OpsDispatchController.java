package ai.neargo.shop.fulfillment.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.fulfillment.dto.ArrivalBatchVO;
import ai.neargo.shop.fulfillment.dto.OverdueRuleVO;
import ai.neargo.shop.fulfillment.dto.RedeemStatVO;
import ai.neargo.shop.fulfillment.dto.SortingRowVO;
import ai.neargo.shop.fulfillment.service.DispatchService;
import ai.neargo.shop.spi.platform.AuditLogPort;
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
 * 平台端 · 履约调度（P-5.1）。
 *
 * <p><b>与 B 端履约的分界</b>：B 端只看自己的货（{@code /biz/pickup/**}），
 * 平台看一个自提点上**所有商家**的货。同一份订单数据的两种切法 ——
 * 数字必须对得上，所以这里的批次件数、分拣行、核销率全部现算自订单，
 * 不另存计数器（B-6.0 的教训：另存的迟早「总览说 3 单、点进去只有 2 单」）。
 *
 * <p>平台**不核销**：核销要扫码、要在现场、要按自提点收敛，
 * 所以 {@link #redeemStats} 是只读监控，没有对应的写动作。
 */
@Profile("ops")
@RestController
@Validated
public class OpsDispatchController {

    private final DispatchService dispatchService;
    private final AuditLogPort auditLogPort;

    public OpsDispatchController(DispatchService dispatchService, AuditLogPort auditLogPort) {
        this.dispatchService = dispatchService;
        this.auditLogPort = auditLogPort;
    }

    @GetMapping("/ops/fulfillment/batches")
    @PreAuthorize("@perm.can('" + Perms.FULFILLMENT_BATCH_READ + "')")
    public PageData<ArrivalBatchVO> batches(@RequestParam(required = false) String communityNo,
                                            @RequestParam(required = false) String pickupNo,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "20") long size) {
        // 运营端列表页按 {records,total} 渲染 —— 返回裸数组会被当成空页
        return PageData.ofAll(dispatchService.batches(communityNo, pickupNo, status, keyword),
                page, size);
    }

    /**
     * 推进批次（发车/到货/签收）。<b>有序推进不许跳步</b>——
     * 跳过「到货」直接签收，等于没人确认过货真的到了那个点。
     */
    @PostMapping("/ops/fulfillment/batches/{batchNo}/status")
    @PreAuthorize("@perm.can('" + Perms.FULFILLMENT_BATCH_READ + "')")
    public ArrivalBatchVO setBatchStatus(@PathVariable String batchNo,
                                         @RequestBody StatusReq req) {
        var vo = dispatchService.setBatchStatus(batchNo, req.status(), SecurityUtils.currentUserNo());
        auditLogPort.record("FULFILLMENT_BATCH", batchNo, "推进到 " + req.status());
        return vo;
    }

    /** 按自提点汇总分拣：一个点上所有商家的货，按商品聚合。 */
    @GetMapping("/ops/fulfillment/sorting")
    @PreAuthorize("@perm.can('" + Perms.FULFILLMENT_BATCH_READ + "')")
    public PageData<SortingRowVO> sorting(@RequestParam(required = false) String pickupNo,
                                          @RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "20") long size) {
        return PageData.ofAll(dispatchService.sorting(pickupNo), page, size);
    }

    /** 核销监控与逾期看板。只读 —— 平台不核销。 */
    @GetMapping("/ops/fulfillment/redeem")
    @PreAuthorize("@perm.can('" + Perms.FULFILLMENT_REDEEM_READ + "')")
    public PageData<RedeemStatVO> redeemStats(@RequestParam(required = false) String pickupNo,
                                              @RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "20") long size) {
        return PageData.ofAll(dispatchService.redeemStats(pickupNo), page, size);
    }

    @GetMapping("/ops/fulfillment/overdue-rule")
    @PreAuthorize("@perm.can('" + Perms.FULFILLMENT_BATCH_READ + "')")
    public OverdueRuleVO overdueRule() {
        return dispatchService.overdueRule();
    }

    /**
     * 逾期处置规则。<b>改它会改变一批订单的命运</b>（顺延还是作废），
     * 所以留痕 —— 用户投诉「我的货怎么没了」时要查得到当时的规则。
     */
    @PostMapping("/ops/fulfillment/overdue-rule")
    @PreAuthorize("@perm.can('" + Perms.FULFILLMENT_RULE_UPDATE + "')")
    public OverdueRuleVO saveOverdueRule(@RequestBody OverdueRuleReq req) {
        var vo = dispatchService.saveOverdueRule(req.action(),
                req.graceHours() == null ? 0 : req.graceHours(),
                req.maxPostpone() == null ? 0 : req.maxPostpone(),
                SecurityUtils.currentUserNo());
        auditLogPort.record("FULFILLMENT_OVERDUE_RULE", "MAIN",
                vo.action() + "｜宽限 " + vo.graceHours() + "h｜顺延上限 " + vo.maxPostpone(), true);
        return vo;
    }

    public record StatusReq(String status) {
    }

    public record OverdueRuleReq(String action, Integer graceHours, Integer maxPostpone) {
    }
}
