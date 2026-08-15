package ai.neargo.shop.trade.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.trade.service.MerchantOrderService;
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
 * 平台端 · 订单（P-4.1）。
 *
 * <p>客服处理任何问题的第一站 —— 此前这里是 404，客服只能让用户自己截图。
 *
 * <p><b>异常单是实时算出来的视图，不落表</b>：落表就会过期，
 * 订单已经推进了而异常记录还挂在那里，运营会去处理一个不存在的问题。
 */
@Profile("ops")
@RestController
@Validated
public class OpsOrderController {

    private final MerchantOrderService orderService;
    private final ai.neargo.shop.trade.service.PlatformOrderService platformOrderService;
    private final AuditLogPort auditLogPort;

    public OpsOrderController(MerchantOrderService orderService,
                              ai.neargo.shop.trade.service.PlatformOrderService platformOrderService,
                              AuditLogPort auditLogPort) {
        this.orderService = orderService;
        this.platformOrderService = platformOrderService;
        this.auditLogPort = auditLogPort;
    }

    /**
     * 订单查询（**旧口径**，S7 从 OpsController 拆出时就在）。
     *
     * <p>与下面的 {@code /ops/orders} 是同一件事的两种形状：这条返回 C/B 端那套
     * {@code OrderVO}，那条返回平台端要的 {@code OpsOrderVO}（带社区、流量来源、
     * 进入当前状态的时刻）。ops-web 声明的是**复数**那条。
     *
     * <p>暂时并存而不是直接删：它已经在契约里被统计过，删掉是一次破坏性变更。
     * 等 ops-web 接真、确认没有别的调用方之后再收敛成一条。
     */
    @GetMapping("/ops/order")
    @PreAuthorize("@perm.can('" + Perms.ORDER_READ + "')")
    public PageData<ai.neargo.shop.trade.dto.OrderVO> legacyOrders(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        // size 上限 100：运营页面没有一次拉全量的正当理由，而没有上限时
        // 一次 size=100000 就能把连接池占满
        return platformOrderService.search(status, page, Math.min(size, 100));
    }

    @GetMapping("/ops/orders")
    @PreAuthorize("@perm.can('" + Perms.ORDER_READ + "')")
    public PageData<MerchantOrderService.OpsOrderVO> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String merchantNo,
            @RequestParam(required = false) String storeNo,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return orderService.opsList(status, merchantNo, storeNo, keyword, page, Math.min(size, 100));
    }

    @GetMapping("/ops/orders/{orderNo}")
    @PreAuthorize("@perm.can('" + Perms.ORDER_READ + "')")
    public MerchantOrderService.OpsOrderVO detail(@PathVariable String orderNo) {
        return orderService.opsDetail(orderNo);
    }

    /** 同一次结算拆出的兄弟单 —— 客服要知道「他这一单还买了别家什么」。 */
    @GetMapping("/ops/orders/parent/{parentNo}")
    @PreAuthorize("@perm.can('" + Perms.ORDER_READ + "')")
    public List<MerchantOrderService.OpsOrderVO> siblings(@PathVariable String parentNo) {
        return orderService.siblings(parentNo);
    }

    @GetMapping("/ops/orders/exceptions")
    @PreAuthorize("@perm.can('" + Perms.ORDER_READ + "')")
    public ai.neargo.shop.common.PageData<MerchantOrderService.OrderExceptionVO> exceptions(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        // 运营端列表页按 {records,total} 渲染 —— 返回裸数组会被当成空页
        return ai.neargo.shop.common.PageData.ofAll(orderService.exceptions(), page, size);
    }

    @GetMapping("/ops/orders/{orderNo}/interventions")
    @PreAuthorize("@perm.can('" + Perms.ORDER_READ + "')")
    public List<MerchantOrderService.InterventionVO> interventions(@PathVariable String orderNo) {
        return orderService.interventions(orderNo);
    }

    /**
     * 人工干预状态。**必须写原因**，且迁移由后端状态机判定。
     *
     * <p>权限用 {@link Perms#ORDER_INTERVENE} 而不是 {@code ORDER_VIEW}：
     * 看单和改单是两件事，前者客服天天做，后者是把系统的判断覆盖掉。
     */
    @PostMapping("/ops/orders/{orderNo}/intervene")
    @PreAuthorize("@perm.can('" + Perms.ORDER_MODIFY + "')")
    public MerchantOrderService.OpsOrderVO intervene(@PathVariable String orderNo,
                                                     @RequestBody InterveneReq req) {
        var vo = orderService.intervene(orderNo, req.to(), req.remark(), SecurityUtils.currentUserNo());
        auditLogPort.record("ORDER_INTERVENE", orderNo, req.to() + "：" + req.remark());
        return vo;
    }

    /**
     * 代客取消。已支付的取消必然退款 —— 契约里不给「不退款」这条路。
     *
     * <p>与 {@link #intervene} 分开一个端点而不是让运营自己选 CANCELLED：
     * 代客取消是一件有明确语义的事（用户打电话来要求取消），
     * 混在通用干预里，事后无法从留痕上区分「用户要求的」与「运营自己改的」。
     */
    @PostMapping("/ops/orders/{orderNo}/proxy-cancel")
    @PreAuthorize("@perm.can('" + Perms.ORDER_PROXY + "')")
    public MerchantOrderService.OpsOrderVO proxyCancel(@PathVariable String orderNo,
                                                       @RequestBody ProxyCancelReq req) {
        String reason = req.reason() == null ? "" : req.reason().trim();
        var vo = orderService.intervene(orderNo, "CANCELLED", "代客取消：" + reason,
                SecurityUtils.currentUserNo());
        auditLogPort.record("ORDER_PROXY_CANCEL", orderNo, reason);
        return vo;
    }

    /** @param to 目标状态，用**展示状态**的词（与端上一致） */
    public record InterveneReq(String to, String remark) {
    }

    /** @param reason 代客取消必须写原因，否则用户来问时没人说得清 */
    public record ProxyCancelReq(String reason) {
    }
}
