package ai.neargo.shop.portal.ops.pay;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.pay.dto.FinanceVOs.RefundSplitBackVO;
import ai.neargo.shop.pay.service.RefundSplitBackService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台端 · 退款回退分账（矩阵 P-12.1.5 / 待完成功能清单 E4，标「高风险」）。
 *
 * <p><b>补的是一个会卡住真钱的缺口</b>：平台裁决支持退款之后，售后单停在
 * {@code REFUNDING} —— {@code arbitrate} 只改状态、不执行退款。
 * 而它对应的子单可能已经分过账。没有这个入口，那笔单子就一直挂着：
 * 买家没收到钱，商家账上多着一笔。
 *
 * <p><b>不分页</b>：这是个待办队列，不是流水。队列长到要翻页时，
 * 该做的是查为什么没人处理，而不是加一个翻页控件。
 *
 * <p>写动作用 {@code finance:settle:execute} 而不是 {@code aftersale:*}：
 * 这个按钮动的是<b>分账</b>（把钱从商家账户收回），不是裁决。
 * 裁决在 `/after-sales` 页由售后组做，这里是财务在裁决之后把资金动作补上。
 */
@Profile("ops")
@RestController
@Validated
public class OpsRefundSplitBackController {

    private final RefundSplitBackService service;
    private final AuditLogPort auditLogPort;

    public OpsRefundSplitBackController(RefundSplitBackService service, AuditLogPort auditLogPort) {
        this.service = service;
        this.auditLogPort = auditLogPort;
    }

    @GetMapping("/ops/refund-split-backs")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public List<RefundSplitBackVO> pending() {
        return service.pending();
    }

    /**
     * 执行：<b>先回退分账，再退款</b>（ADR-002，顺序不可交换）。
     *
     * <p>回退失败即中止且<b>不退款</b> —— 钱还在商家账户上就退给买家，
     * 平台要垫付这笔差额。
     */
    @PostMapping("/ops/refund-split-backs/{afterSaleNo}/execute")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_EXECUTE + "')")
    public RefundSplitBackVO execute(@PathVariable String afterSaleNo) {
        String operator = SecurityUtils.currentUserNo();
        RefundSplitBackVO vo = service.execute(afterSaleNo, operator);
        auditLogPort.record("REFUND_SPLIT_BACK", afterSaleNo,
                "回退分账并退款 " + vo.refundMinor() + " 分", true);
        return vo;
    }
}
