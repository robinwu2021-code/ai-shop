package ai.neargo.shop.merchant.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.merchant.service.MerchantPlanService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台端 · 增值包与门店额度（P-11.2.2~11.2.6）。
 *
 * <p>一期**不接支付**：商家点「升级」→ 联系平台 → 运营在这里授予
 * （{@code granted_by=PLATFORM}）。「商家 → 平台」的收款方向一条链路都没有，
 * 接它涉及通道签约、发票、退订退款，是独立项目 —— 而在验证「有没有人愿意买」
 * 之前建那条链路，是拿最贵的一步去赌一个未验证的假设。
 *
 * <p>权限沿用**商家治理**那两个码，不新造：读挂 {@code merchant:merchant:read}，
 * 授予与额度覆盖挂 {@code merchant:merchant:ban}（它们与封禁同属处置面 ——
 * 决定一家商家能开几家店，和决定他能不能营业是同一个量级的动作）。
 */
@Profile("ops")
@RestController
@Validated
public class OpsMerchantPlanController {

    private final MerchantPlanService planService;
    private final AuditLogPort auditLogPort;

    public OpsMerchantPlanController(MerchantPlanService planService, AuditLogPort auditLogPort) {
        this.planService = planService;
        this.auditLogPort = auditLogPort;
    }

    /**
     * 到期与降级看板（P-11.2.5）。
     *
     * <p>三个筛选各对应一个动作：**快到期**去催、**宽限期中**去救、**已降级**去回访 ——
     * 而「降级后掉了多少单」是判断定价合不合理的依据。
     */
    @GetMapping("/ops/merchant-plans")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_READ + "')")
    public PageData<MerchantPlanService.PlanRowVO> plans(
            @RequestParam(required = false) String filter,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return planService.search(filter, keyword, page, Math.min(size, 100));
    }

    @GetMapping("/ops/merchant-plans/upgrade-signals")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_READ + "')")
    public List<MerchantPlanService.UpgradeSignalVO> upgradeSignals() {
        return planService.upgradeSignals();
    }

    /**
     * 授予 / 延长（P-11.2.2）。
     *
     * <p>快照按迁移类型刷新：换档与续费重读档位定义，**只补缴不延长则不动快照** ——
     * 他买的是当初那个额度，中途运营下调档位定义不该殃及他。
     */
    @PostMapping("/ops/merchant-plans/{merchantNo}/grant")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_BAN + "')")
    public MerchantPlanService.PlanRowVO grant(@PathVariable String merchantNo,
                                               @RequestBody GrantReq req) {
        var vo = planService.grant(merchantNo, req.planCode(), req.months(), req.reason(),
                SecurityUtils.currentUserNo());
        // critical：它直接决定这家商家能开几家店
        auditLogPort.record("PLAN_GRANT", merchantNo,
                req.planCode() + "｜" + (req.months() == null ? "仅补缴" : req.months() + " 个月")
                        + "｜" + req.reason(), true);
        return vo;
    }

    /** 单商家额度覆盖（P-11.2.4）。传 null 清除覆盖、回到档位快照。 */
    @PutMapping("/ops/merchant-plans/{merchantNo}/quota")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_BAN + "')")
    public MerchantPlanService.PlanRowVO overrideQuota(@PathVariable String merchantNo,
                                                       @RequestBody QuotaReq req) {
        var vo = planService.overrideQuota(merchantNo, req.storeQuota(), req.staffQuota(),
                req.reason(), SecurityUtils.currentUserNo());
        auditLogPort.record("PLAN_QUOTA_OVERRIDE", merchantNo,
                "门店 " + req.storeQuota() + " / 子账号 " + req.staffQuota() + "｜" + req.reason(),
                true);
        return vo;
    }

    /**
     * 档位定义（P-11.2.3）。
     *
     * <p>返回体带 {@code subscriberCount} —— **改定义的人必须看得到「有几家在用」**：
     * 它是「只影响之后新订阅的人」那句话的具体量。不给这个数，运营只能凭感觉判断影响面。
     */
    @GetMapping("/ops/plan-defs")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_READ + "')")
    public List<MerchantPlanService.PlanDefVO> defs() {
        return planService.defs();
    }

    /**
     * 改档位定义。**只影响之后新订阅的人**，已订阅的用的是自己的额度快照。
     *
     * <p>刻意<b>不用 {@link Perms#MERCHANT_BAN}</b>（授予用的那个码），而用
     * {@code system:param:update} —— 这是一次职责分离：<b>BD 能给某家商家授予套餐，
     * 但不能改「套餐是什么」</b>。前者影响一家，后者影响这一档之后的所有订阅，
     * 与「功能开关、灰度、汇率」是同一个量级的动作，正是那个码的定义。
     *
     * <p>读（{@link #defs()}）反过来挂 {@link Perms#MERCHANT_READ}：授予对话框要
     * 拿档位列表填下拉，BD 看不到档位就没法授予。
     */
    @PutMapping("/ops/plan-defs/{planCode}")
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_PARAM_UPDATE + "')")
    public MerchantPlanService.PlanDefVO saveDef(@PathVariable String planCode,
                                                 @RequestBody DefReq req) {
        var vo = planService.saveDef(planCode, req.storeQuota(), req.staffQuota(),
                Boolean.TRUE.equals(req.crossStoreStats()), req.trialDays(),
                !Boolean.FALSE.equals(req.enabled()), SecurityUtils.currentUserNo());
        auditLogPort.record("PLAN_DEF", planCode,
                "门店 " + req.storeQuota() + " / 子账号 " + req.staffQuota()
                        + "｜影响 " + vo.subscriberCount() + " 家的后续新订阅", true);
        return vo;
    }

    /** @param months 延长月数；null 或 0 = 只补缴不延长（不刷新额度快照） */
    public record GrantReq(String planCode, Integer months, String reason) {
    }

    /** @param storeQuota null = 清除覆盖、回到档位快照 */
    public record QuotaReq(Integer storeQuota, Integer staffQuota, String reason) {
    }

    public record DefReq(int storeQuota, int staffQuota, Boolean crossStoreStats,
                         int trialDays, Boolean enabled) {
    }
}
