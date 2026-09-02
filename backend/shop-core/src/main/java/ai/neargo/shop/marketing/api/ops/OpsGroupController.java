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
 * <p><b>审核是一个开关，两种形态都成立</b>（{@code group.audit}，默认 <b>关</b>）：
 * <ul>
 *   <li><b>关</b>（默认，也是加开关之前的行为）：建团即 {@code OPEN}，商家的开团体验一个字不变。
 *       此时不会有任何团处在 {@code PENDING}，审核那一页天然是空的</li>
 *   <li><b>开</b>：建团落 {@code PENDING}，审过才进 {@code OPEN}。
 *       C 端只列 {@code OPEN/FORMED}、参团只认 {@code OPEN}，所以 {@code PENDING} 天然被挡住 ——
 *       这也是这里选新状态而不是加一个布尔位的原因：加布尔位要在每个读的地方补判断，漏一处就是没审就上线</li>
 * </ul>
 *
 * <p><b>仍然不做「强制成团」</b>：中止是止损，成团是替商家做生意决定 ——
 * 后者一旦出错（商家备不出货），承担后果的是不知情的用户。
 * {@code /status} 只放行 {@code STATUS_MOVES} 里那几条迁移，终态改不回去。
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
     * 审核待上线的团（开关 {@code group.audit} 打开时才会有待审的团）。
     *
     * <p>通过 → C 端可见、可参团；驳回 → 团直接失败，<b>理由商家原样看到</b>。
     */
    @PostMapping("/ops/groups/{groupNo}/audit")
    @PreAuthorize("@perm.can('" + Perms.GROUP_CAMPAIGN_AUDIT + "')")
    public GroupBuyVO audit(@PathVariable String groupNo, @RequestBody AuditReq req) {
        String operator = SecurityUtils.currentUserNo();
        boolean pass = Boolean.TRUE.equals(req.pass());
        GroupBuyVO vo = groupService.auditGroup(groupNo, pass, req.reason(), operator);
        auditLogPort.record("GROUP_AUDIT", groupNo,
                (pass ? "通过" : "驳回") + (req.reason() == null ? "" : "｜" + req.reason()));
        return vo;
    }

    /** @param pass 通过或驳回；驳回时 reason 必填 */
    public record AuditReq(Boolean pass, String reason) {
    }

    /**
     * 直接改团的状态（跨状态干预）。**只走合法迁移** ——
     * 已成团/已失败是终态，改回去不会把钱退给任何人，只会让订单与团对不上。
     */
    @PostMapping("/ops/groups/{groupNo}/status")
    @PreAuthorize("@perm.can('" + Perms.GROUP_CAMPAIGN_AUDIT + "')")
    public GroupBuyVO setStatus(@PathVariable String groupNo, @RequestBody StatusReq req) {
        String operator = SecurityUtils.currentUserNo();
        GroupBuyVO vo = groupService.setGroupStatus(groupNo, req.status(), operator);
        auditLogPort.record("GROUP_STATUS", groupNo, String.valueOf(req.status()));
        return vo;
    }

    public record StatusReq(String status) {
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
