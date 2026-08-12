package ai.neargo.shop.trade.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.spi.platform.SettingPort;
import ai.neargo.shop.trade.dto.AfterSaleVO;
import ai.neargo.shop.trade.service.AfterSaleService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 平台端 · 售后仲裁（P-6.1）。
 *
 * <p><b>ARBITRATING 此前只有入口没有出口</b>：用户能把争议上升到平台，
 * 而平台没有任何接口能裁 —— 单子停在那里，用户和商家都在等一个不会来的结果。
 * 与差评申诉是同一个形状的缺口。
 */
@Profile("ops")
@RestController
@Validated
public class OpsAfterSaleController {

    /** 极速退阈值的参数键与默认值。默认关闭 —— 自动退款的开关默认开着是件危险的事 */
    private static final String FAST_REFUND_KEY = "aftersale.fast-refund-rule";
    private static final String FAST_REFUND_DEFAULT =
            "{\"enabled\":false,\"maxAmount\":2000,\"withinHours\":24,\"categories\":[]}";

    private final AfterSaleService afterSaleService;
    private final SettingPort settingPort;
    private final AuditLogPort auditLogPort;
    private final ObjectMapper json;

    public OpsAfterSaleController(AfterSaleService afterSaleService, SettingPort settingPort,
                                  AuditLogPort auditLogPort, ObjectMapper json) {
        this.afterSaleService = afterSaleService;
        this.settingPort = settingPort;
        this.auditLogPort = auditLogPort;
        this.json = json;
    }

    @GetMapping("/ops/after-sales")
    @PreAuthorize("@perm.can('" + Perms.AFTERSALE_TICKET_READ + "')")
    public ai.neargo.shop.common.PageData<AfterSaleVO> list(@RequestParam(required = false) String status,
                                @RequestParam(required = false) String merchantNo,
                                @RequestParam(defaultValue = "1") long page,
                                @RequestParam(defaultValue = "20") long size) {
        // 运营端列表页按 {records,total} 渲染 —— 返回裸数组会被当成空页
        return ai.neargo.shop.common.PageData.ofAll(afterSaleService.opsList(status, merchantNo), page, size);
    }

    /**
     * 平台裁决。{@code refund=true} 支持用户（推进到退款），false 维持商家决定（关闭）。
     * <b>责任方与裁决说明都必填</b>。
     */
    @PostMapping("/ops/after-sales/{afterSaleNo}/decide")
    @PreAuthorize("@perm.can('" + Perms.AFTERSALE_TICKET_HANDLE + "')")
    public AfterSaleVO decide(@PathVariable String afterSaleNo, @RequestBody DecideReq req) {
        boolean refund = Boolean.TRUE.equals(req.refund());
        var vo = afterSaleService.arbitrate(afterSaleNo, refund, req.liability(), req.verdict(),
                SecurityUtils.currentUserNo());
        // 裁决动的是真金白银，必须能追到是谁在什么时候裁的
        auditLogPort.record("AFTER_SALE_ARBITRATE", afterSaleNo,
                (refund ? "支持退款" : "维持商家决定") + "｜责任方 " + req.liability() + "｜" + req.verdict());
        return vo;
    }

    // ---------------------------------------------------------------- 极速退阈值

    @GetMapping("/ops/after-sales/fast-refund-rule")
    @PreAuthorize("@perm.can('" + Perms.AFTERSALE_REFUND_READ + "')")
    public FastRefundRule fastRefundRule() {
        return json.readValue(settingPort.get(FAST_REFUND_KEY, FAST_REFUND_DEFAULT),
                FastRefundRule.class);
    }

    /**
     * 保存极速退阈值。
     *
     * <p>{@code withinHours} 必须 ≥ 1：0 小时等于把功能关掉，但开关看起来还是开着的 ——
     * 运营会以为极速退在跑，实际每一单都进了人工队列。
     */
    @PostMapping("/ops/after-sales/fast-refund-rule")
    @PreAuthorize("@perm.can('" + Perms.AFTERSALE_REFUND_APPROVE + "')")
    public FastRefundRule saveFastRefundRule(@RequestBody FastRefundRule req) {
        if (req.maxAmount() <= 0 || req.withinHours() < 1) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        settingPort.put(FAST_REFUND_KEY, json.writeValueAsString(req), SecurityUtils.currentUserNo());
        // 这个阈值决定多少钱可以**不经人工**退出去，改动必须留痕
        auditLogPort.record("FAST_REFUND_RULE", FAST_REFUND_KEY,
                "%s｜上限 %d 分｜%d 小时内".formatted(
                        req.enabled() ? "开启" : "关闭", req.maxAmount(), req.withinHours()));
        return req;
    }

    /** @param liability PLATFORM / MERCHANT / PICKUP */
    public record DecideReq(Boolean refund, String liability, String verdict) {
    }

    /** @param categories 适用品类编码，空 = 全品类 */
    public record FastRefundRule(boolean enabled, long maxAmount, int withinHours,
                                 List<String> categories) {
    }
}
