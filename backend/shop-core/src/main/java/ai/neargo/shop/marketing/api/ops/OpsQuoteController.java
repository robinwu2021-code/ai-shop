package ai.neargo.shop.marketing.api.ops;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.marketing.group.dto.OpsGroupVOs;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.marketing.group.GroupService;
import ai.neargo.shop.marketing.group.dto.GroupVOs.QuoteVO;
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
 * 平台端 · 报价治理（P-8.2）。
 *
 * <p>此前平台对报价**没有任何干预手段**：商家把价格写错一位数、或者接了单又不认，
 * 运营只能去改数据库。而 {@code /ops/quotes/{no}/breach} 在契约里声明着，
 * 状态列却连 {@code BREACH} 这个取值都没有——上下游都建好了，中间少一个枚举值。
 * 这种缺口比缺表难发现：它不会在任何「表建了没有」的检查里露头。
 */
@Profile("ops")
@RestController
@Validated
public class OpsQuoteController {

    private final GroupService groupService;
    private final AuditLogPort auditLogPort;

    public OpsQuoteController(GroupService groupService, AuditLogPort auditLogPort) {
        this.groupService = groupService;
        this.auditLogPort = auditLogPort;
    }

    /** @param status 为空给全部；传 {@code BREACH} 就是毁约档 */
    @GetMapping("/ops/quotes")
    @PreAuthorize("@perm.can('" + Perms.QUOTE_GOVERN + "')")
    public PageData<OpsGroupVOs.OpsQuoteVO> list(@RequestParam(required = false) String status,
                                  @RequestParam(defaultValue = "1") long page,
                                  @RequestParam(defaultValue = "50") long size) {
        // 运营端列表页按 {records,total} 渲染 —— 返回裸数组会被当成空页
        return PageData.ofAll(groupService.opsQuotes(status), page, size);
    }

    /** 平台改价（P-8.2.4）。留痕与商家改价同一条路径，公示同一份价格历史。 */
    @PostMapping("/ops/quotes/{quoteNo}/price")
    @PreAuthorize("@perm.can('" + Perms.QUOTE_GOVERN + "')")
    public QuoteVO price(@PathVariable String quoteNo, @RequestBody PriceReq req) {
        String operator = SecurityUtils.currentUserNo();
        QuoteVO vo = groupService.opsRevisePrice(quoteNo, req.unitPriceMinor(), req.reason(), operator);
        auditLogPort.record("QUOTE_PRICE", quoteNo,
                "改为 %d 分｜%s".formatted(req.unitPriceMinor(), req.reason()));
        return vo;
    }

    /**
     * 判定毁约（P-8.2.5）。写入商家信用档案，**不可撤销**。
     * {@code detail} 必填——没有事实的处置在申诉时站不住。
     */
    @PostMapping("/ops/quotes/{quoteNo}/breach")
    @PreAuthorize("@perm.can('" + Perms.QUOTE_GOVERN + "')")
    public QuoteVO breach(@PathVariable String quoteNo, @RequestBody BreachReq req) {
        String operator = SecurityUtils.currentUserNo();
        QuoteVO vo = groupService.markBreach(quoteNo, req.detail(), operator);
        auditLogPort.record("QUOTE_BREACH", quoteNo, req.detail());
        return vo;
    }

    public record PriceReq(long unitPriceMinor, String reason) {
    }

    public record BreachReq(String detail) {
    }
}
