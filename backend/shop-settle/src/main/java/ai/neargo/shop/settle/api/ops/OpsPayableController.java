package ai.neargo.shop.settle.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.settle.SettleService;
import ai.neargo.shop.settle.dto.SettleBillVO;
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

import java.util.List;

/**
 * 平台端 · 自营应付账款（经营模式双轨方案 P0-9）。
 *
 * <p>自营模式下平台向供应商采购，这一组接口是**财务的操作台**：
 * 对账 → 收票核验 → 登记付款。
 *
 * <p><b>系统只登记不划转</b>：真正的打款是财务在网银执行的。
 * 让业务系统去动公司对公账户是财务内控问题，不是技术能力问题——
 * 任何一家公司的财务都不会同意。
 *
 * <p>第三方模式的钱走分账，不经过这里（调用会被 `CONFLICT` 拒绝）。
 */
@Profile("ops")
@RestController
@Validated
public class OpsPayableController {

    private final SettleService settleService;
    private final AuditLogPort auditLogPort;

    public OpsPayableController(SettleService settleService, AuditLogPort auditLogPort) {
        this.settleService = settleService;
        this.auditLogPort = auditLogPort;
    }

    /**
     * @param status   为空给全部；{@code PENDING_RECON} 就是待对账队列
     * @param entityNo 为空给全部供应商
     */
    @GetMapping("/ops/payables")
    @PreAuthorize("@perm.can('" + Perms.SETTLE_MANAGE + "')")
    public List<SettleBillVO> list(@RequestParam(required = false) String status,
                                   @RequestParam(required = false) String entityNo) {
        return settleService.opsPayables(status, entityNo);
    }

    /** 对账确认。确认的含义是「双方认这个数」，之后金额不该再变。 */
    @PostMapping("/ops/payables/{settleNo}/confirm")
    @PreAuthorize("@perm.can('" + Perms.SETTLE_MANAGE + "')")
    public SettleBillVO confirm(@PathVariable String settleNo) {
        String operator = SecurityUtils.currentUserNo();
        SettleBillVO vo = settleService.confirmRecon(settleNo, operator);
        auditLogPort.record("PAYABLE_CONFIRM", settleNo, "对账确认，应付 " + vo.netMinor() + " 分");
        return vo;
    }

    /**
     * 登记已付。**票到付款**——进项票未核验通过的单会被拒。
     *
     * <p>凭证号必填：没有凭证号的「已付」事后对不上银行流水，也说不清是谁付的。
     */
    @PostMapping("/ops/payables/{settleNo}/paid")
    @PreAuthorize("@perm.can('" + Perms.SETTLE_MANAGE + "')")
    public SettleBillVO paid(@PathVariable String settleNo, @RequestBody PaidReq req) {
        String operator = SecurityUtils.currentUserNo();
        SettleBillVO vo = settleService.markPaid(settleNo, req.paymentRef(), operator);
        // 钱出账的登记必须留痕：事后追责靠的就是「谁在什么时候登记了哪张凭证」
        auditLogPort.record("PAYABLE_PAID", settleNo,
                "凭证 " + req.paymentRef() + "｜金额 " + vo.netMinor() + " 分");
        return vo;
    }

    /**
     * 标记无票供应商。之后这张单可以直接付款。
     *
     * <p><b>标出而不是禁止</b>：现实中总会有例外，禁止会逼人绕过系统；
     * 标出则让每一笔例外都是被看见的——财务在付款前就知道
     * 「这笔付出去是不能税前列支的」，而不是月末报税时才发现。
     */
    @PostMapping("/ops/payables/{settleNo}/no-invoice")
    @PreAuthorize("@perm.can('" + Perms.SETTLE_MANAGE + "')")
    public SettleBillVO noInvoice(@PathVariable String settleNo, @RequestBody NoInvoiceReq req) {
        String operator = SecurityUtils.currentUserNo();
        SettleBillVO vo = settleService.markNoInvoice(settleNo, req.reason(), operator);
        auditLogPort.record("PAYABLE_NO_INVOICE", settleNo, req.reason());
        return vo;
    }

    public record PaidReq(String paymentRef) {
    }

    public record NoInvoiceReq(String reason) {
    }
}
