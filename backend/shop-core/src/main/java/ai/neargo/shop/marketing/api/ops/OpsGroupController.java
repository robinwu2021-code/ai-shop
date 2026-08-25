package ai.neargo.shop.marketing.api.ops;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.marketing.group.dto.OpsGroupVOs;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.marketing.group.GroupService;
import ai.neargo.shop.marketing.group.dto.GroupVOs.GroupBuyVO;
import ai.neargo.shop.marketing.group.dto.GroupVOs.RequestVO;
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
 * 平台端 · 拼团治理（P-8.1）。
 *
 * <p>此前平台对拼团**零干预手段**：商家开了个违规团、或者把原价标高再打「团购价」，
 * 运营只能去改数据库。
 *
 * <p><b>只做「中止」，不做「审核」与「强制成团」</b>，两个都不是漏掉的：
 * <ul>
 *   <li><b>审核</b>：ops-web 契约里的 {@code /ops/groups/{no}/audit} 假定团要先审后开
 *       （它的状态机有 {@code PENDING}），而后端的团是即开即跑（{@code OPEN}）。
 *       补这条等于改变商家的开团体验，是产品决策不是补齐</li>
 *   <li><b>强制成团</b>：中止是止损，成团是替商家做生意决定——
 *       后者一旦出错（商家备不出货），承担后果的是不知情的用户</li>
 * </ul>
 */
@Profile("ops")
@RestController
@Validated
public class OpsGroupController {

    private final GroupService groupService;
    private final AuditLogPort auditLogPort;

    public OpsGroupController(GroupService groupService, AuditLogPort auditLogPort) {
        this.groupService = groupService;
        this.auditLogPort = auditLogPort;
    }

    /** @param status 为空给全部；{@code OPEN} / {@code FORMED} / {@code FAILED} */
    @GetMapping("/ops/groups")
    @PreAuthorize("@perm.can('" + Perms.GROUP_CAMPAIGN_READ + "')")
    public PageData<OpsGroupVOs.OpsGroupVO> list(@RequestParam(required = false) String status,
                                     @RequestParam(defaultValue = "1") long page,
                                     @RequestParam(defaultValue = "50") long size) {
        // 运营端列表页按 {records,total} 渲染 —— 返回裸数组会被当成空页
        return PageData.ofAll(groupService.opsGroups(status), page, size);
    }

    /** 中止拼团。理由必填 —— 团没了总得给参团的人一个说法。 */
    @PostMapping("/ops/groups/{groupNo}/abort")
    @PreAuthorize("@perm.can('" + Perms.GROUP_CAMPAIGN_AUDIT + "')")
    public GroupBuyVO abort(@PathVariable String groupNo, @RequestBody AbortReq req) {
        String operator = SecurityUtils.currentUserNo();
        GroupBuyVO vo = groupService.abortGroup(groupNo, req.reason(), operator);
        auditLogPort.record("GROUP_ABORT", groupNo, req.reason());
        return vo;
    }

    public record AbortReq(String reason) {
    }

    /**
     * 需求单池（平台视角）。运营监控：哪个邻居在等什么货、报价进展。
     *
     * @param status 为空给全部；{@code COLLECTING}/{@code QUOTED}/{@code CLOSED}
     */
    @GetMapping("/ops/demands")
    @PreAuthorize("@perm.can('" + Perms.GROUP_DEMAND_READ + "')")
    public PageData<RequestVO> demands(@RequestParam(required = false) String status,
                                      @RequestParam(defaultValue = "1") long page,
                                      @RequestParam(defaultValue = "50") long size) {
        return PageData.ofAll(groupService.opsDemands(status), page, size);
    }

    /**
     * 运营人肉指派商家报价（P-8.2.2）。
     *
     * <p>初期靠运营撮合：需求单有了就通知相关商家，商家联系不上时由运营代理报价。
     * 信用约束（毁约 ≥3 次禁止报价）在 GroupService.quote() 里执行，
     * 这里不另设 —— 不在一处就迟早分岔。
     *
     * @param demandNo 需求单号（路径）
     */
    @PostMapping("/ops/demands/{demandNo}/quotes")
    @PreAuthorize("@perm.can('" + Perms.GROUP_DEMAND_ASSIGN + "')")
    public QuoteVO assignQuote(@PathVariable String demandNo, @RequestBody AssignQuoteReq req) {
        String operator = SecurityUtils.currentUserNo();
        QuoteVO vo = groupService.quote(req.merchantNo(), demandNo,
                new GroupService.QuoteCommand(req.price(), req.minQty(), req.note(), req.validDays()));
        auditLogPort.record("DEMAND_QUOTE_ASSIGN", demandNo,
                "为 " + req.merchantNo() + " 指派报价，单价 " + req.price());
        return vo;
    }

    public record AssignQuoteReq(String merchantNo, long price, int minQty,
                                 String note, int validDays) {
    }
}
