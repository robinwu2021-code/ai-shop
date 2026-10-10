package ai.neargo.shop.trade.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.trade.service.InvoiceRequestService;
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
 * 平台端 · 开票申请处理（销项票）。
 *
 * <p><b>与「采购发票核验」不是一回事，别在同一页里混</b>：
 * 那边是<b>进项</b>（供应商开给平台，决定平台能不能列支成本），
 * 这边是<b>销项</b>（平台开给消费者，决定归集资金模式成不成立）。
 * 义务人、方向、法律后果都相反 —— 权限码复用 {@code finance:invoice:*}，
 * 因为经办的是同一批财务人员。
 *
 * <p>本版是<b>手工开票</b>：运营在票据系统里开完，回来回填票号。
 * 接系统是第二步，届时在 {@code ISSUED} 之后延长状态机。
 */
@Profile("ops")
@RestController
@Validated
public class OpsInvoiceRequestController {

    private final InvoiceRequestService service;

    public OpsInvoiceRequestController(InvoiceRequestService service) {
        this.service = service;
    }

    /*
     * ⚠️ 这三个端点原来写的是 {@code hasAuthority(...)}，而运营端的认证过滤器
     * **只塞 ROLE_OPERATOR 与 ROLE_<角色> 两类权威，从不把权限码塞成 authority**。
     * 于是 hasAuthority('finance:invoice:read') 精确匹配一个根本不存在的权威 ——
     * **对所有运营（包括超管）永远 403**，整个开票申请功能是死的，
     * 而它编译通过、测试不报、页面上只表现为「点了没反应」。
     *
     * 运营端的判权入口只有 @perm.can / @perm.canAny（它们读的是每请求现算的权限码，
     * 且认 * 与 module:* 通配）。2026-08-29 由 OpsEndpointPermTest 揪出，
     * 那道闸现在会直接拒绝 hasAuthority 的写法。
     */
    @GetMapping("/ops/invoice-requests")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_INVOICE_READ + "')")
    public List<InvoiceRequestService.InvoiceRequestVO> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(status, page, size);
    }

    @PostMapping("/ops/invoice-requests/{requestNo}/issued")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_INVOICE_VERIFY + "')")
    public InvoiceRequestService.InvoiceRequestVO markIssued(
            @PathVariable String requestNo, @RequestBody IssuedReq req) {
        return service.markIssued(requestNo, req.invoiceNo(), SecurityUtils.currentUserNo());
    }

    @PostMapping("/ops/invoice-requests/{requestNo}/reject")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_INVOICE_VERIFY + "')")
    public InvoiceRequestService.InvoiceRequestVO reject(
            @PathVariable String requestNo, @RequestBody RejectReq req) {
        return service.reject(requestNo, req.reason(), SecurityUtils.currentUserNo());
    }

    public record IssuedReq(String invoiceNo) {
    }

    public record RejectReq(String reason) {
    }
}
