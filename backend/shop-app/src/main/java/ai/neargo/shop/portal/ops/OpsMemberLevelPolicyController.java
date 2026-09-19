package ai.neargo.shop.portal.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.member.service.LevelPolicy;
import ai.neargo.shop.member.service.MemberLevelService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 会员分层口径（原型 o02）。写法同 {@code OpsInventoryPolicyController}：
 * 看用业务域的读权限，改用 {@code system:param:update} —— 口径一改，
 * 全平台所有商家的「沉睡」人数跟着变，那是平台参数，不是看会员的人该按的开关。
 *
 * <p>保存<b>不触发重算</b>：下一轮凌晨任务生效；要马上看效果，去「定时任务」手动跑
 * {@code member-level-recompute}。改完就重算的话，一次误操作会在几秒内改掉全平台的分层。
 */
@Profile("ops")
@RestController
public class OpsMemberLevelPolicyController {

    private final MemberLevelService levelService;
    private final AuditLogPort auditLog;

    public OpsMemberLevelPolicyController(MemberLevelService levelService, AuditLogPort auditLog) {
        this.levelService = levelService;
        this.auditLog = auditLog;
    }

    @PreAuthorize("@perm.can('" + Perms.MEMBER_MEMBER_READ + "')")
    @GetMapping("/ops/members/level-policy")
    public PolicyVO policy() {
        return vo(levelService.policy());
    }

    @PreAuthorize("@perm.can('" + Perms.SYSTEM_PARAM_UPDATE + "')")
    @PostMapping("/ops/members/level-policy")
    public PolicyVO save(@RequestBody LevelPolicy req) {
        LevelPolicy saved = levelService.savePolicy(req, SecurityUtils.currentUserNo());
        // 三个月后有人问「沉睡怎么突然多了一倍」，要查得到是谁、改成了什么
        auditLog.record("MEMBER_LEVEL_POLICY", LevelPolicy.KEY,
                "沉睡=%d天 熟客≥%d单 常客≥%d单".formatted(saved.sleepDays(),
                        saved.loyalD90Orders(), saved.regularD90Orders()), true);
        return vo(saved);
    }

    private PolicyVO vo(LevelPolicy p) {
        return new PolicyVO(p.sleepDays(), p.loyalD90Orders(), p.regularD90Orders(),
                levelService.lastRun());
    }

    /**
     * @param lastRun 上一次重算：时刻、变更人数、其中新变沉睡人数。从没跑过为 null ——
     *                页面据此提示「重算任务还没跑过」，而不是显示一排 0
     */
    public record PolicyVO(int sleepDays, int loyalD90Orders, int regularD90Orders,
                           MemberLevelService.RecomputeResult lastRun) {
    }
}
