package ai.neargo.shop.portal.ops;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.platform.OpsService;
import ai.neargo.shop.platform.Perms;
import ai.neargo.shop.platform.dto.OpsVOs.AuditLogVO;
import ai.neargo.shop.platform.dto.OpsVOs.LoginResultVO;
import ai.neargo.shop.platform.dto.OpsVOs.MerchantApplyVO;
import ai.neargo.shop.platform.dto.OpsVOs.StaffVO;
import ai.neargo.shop.product.dto.GoodsVO;
import ai.neargo.shop.product.service.GoodsService;
import ai.neargo.shop.trade.dto.OrderVO;
import ai.neargo.shop.trade.service.PlatformOrderService;
import jakarta.validation.constraints.NotBlank;
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
 * 平台端（[API 清单 §4]）。
 *
 * <p>**授权只在这一层**（{@code @PreAuthorize}），Service 不判功能权限 ——
 * 三层契约（TDD-backend §3.1）在这里第一次真正用上：把授权散进 Service，
 * 同一个业务方法被两个入口调用时就会漏掉一处。
 */
@RestController
@Validated
public class OpsController {

    private final OpsService opsService;
    private final GoodsService goodsService;
    private final PlatformOrderService platformOrderService;

    public OpsController(OpsService opsService, GoodsService goodsService,
                         PlatformOrderService platformOrderService) {
        this.opsService = opsService;
        this.goodsService = goodsService;
        this.platformOrderService = platformOrderService;
    }

    /** 运营登录。唯一免鉴权的 /ops 端点。 */
    @PostMapping("/ops/auth/login")
    public LoginResultVO login(@RequestBody LoginReq req) {
        return opsService.login(req.username(), req.password());
    }

    @GetMapping("/ops/auth/me")
    public StaffVO me() {
        return opsService.me();
    }

    @GetMapping("/ops/staff")
    @PreAuthorize("@perm.can('" + Perms.STAFF_MANAGE + "')")
    public List<StaffVO> staff() {
        return opsService.staffList();
    }

    @GetMapping("/ops/audit-log")
    @PreAuthorize("@perm.can('" + Perms.AUDIT_LOG_VIEW + "')")
    public List<AuditLogVO> auditLogs(@RequestParam(required = false) String target) {
        return opsService.auditLogs(target);
    }

    @GetMapping("/ops/merchant/apply-queue")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_AUDIT + "')")
    public List<MerchantApplyVO> applyQueue() {
        return opsService.applyQueue();
    }

    @PostMapping("/ops/merchant/apply/{applyNo}/audit")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_AUDIT + "')")
    public void auditApply(@PathVariable String applyNo, @RequestBody AuditReq req) {
        opsService.auditApply(applyNo, Boolean.TRUE.equals(req.approved()), req.reason());
    }

    @GetMapping("/ops/goods/audit-queue")
    @PreAuthorize("@perm.can('" + Perms.GOODS_AUDIT + "')")
    public PageData<GoodsVO> goodsAuditQueue(@RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "20") long size) {
        return goodsService.list(new GoodsService.GoodsQuery(null, null, null, null, null, page, size));
    }

    @GetMapping("/ops/order")
    @PreAuthorize("@perm.can('" + Perms.ORDER_VIEW + "')")
    public PageData<OrderVO> orders(@RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "1") long page,
                                    @RequestParam(defaultValue = "20") long size) {
        return platformOrderService.search(status, page, Math.min(size, 100));
    }

    public record LoginReq(@NotBlank String username, @NotBlank String password) {
    }

    public record AuditReq(Boolean approved, String reason) {
    }
}
