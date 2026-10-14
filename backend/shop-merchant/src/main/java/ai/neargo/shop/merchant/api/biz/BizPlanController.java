package ai.neargo.shop.merchant.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.merchant.service.MerchantPlanService;
import ai.neargo.shop.merchant.service.MerchantPlanService.MinePlanVO;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家端 · 我的增值包（B-11.13，执行计划 P4 步骤 4.0/4.4）。
 *
 * <p><b>这个接口不是可选的</b>，与 {@link BizDepositController} 是同一条理由：
 * 在它之前，商家建第二家店被 70020 拦下、跨店总览被 70023 拦下，
 * 却<b>无处得知自己是哪一档、额度是几、还差什么</b> —— 只能来问客服。
 * 一道对商家不透明的闸门，在他那边就是一次故障。
 *
 * <p><b>挂 {@code biz:store:admin} 而不是更宽的码</b>：这一页答的是「主体买了什么」，
 * 与建店、停用、挂收款号同属主体结构面，而那个码<b>只在老板手里</b>
 * （{@code BizPerms} 刻意不让它进自定义角色）。店长看不到套餐是对的 ——
 * 他不决定要不要升档，而额度数字会让他去催老板买单。
 * 端上按 {@code can('biz:store:admin')} 决定要不要渲染这个入口。
 */
@Profile("api")
@RestController
@Validated
public class BizPlanController {

    private final MerchantPlanService planService;

    public BizPlanController(MerchantPlanService planService) {
        this.planService = planService;
    }

    /**
     * 我的档位、用量与三档对比。
     *
     * <p><b>用量由后端算</b>：端上拿门店列表自己数会与额度闸的口径分岔
     * （闸门只数 {@code ACTIVE}，端上的列表通常含停用的店）。分岔的表现是
     * 「页面说 3/3 已满，实际还能建一家」，而两边都觉得自己没错。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @GetMapping("/biz/plan")
    public MinePlanVO plan() {
        return planService.mine(BizContext.requireMerchantNo());
    }

    /**
     * 自助开通试用（步骤 4.4）。**一主体一次，永不回退**。
     *
     * <p>为什么给自助而不是走运营授予：需求里意图最明确的一次就是「他正要建第二家店」——
     * 那一刻让他去联系平台、等回复，是把一次已经表达出来的购买意图晾成一次流失。
     *
     * <p>能不能点由 {@link MinePlanVO#trialTier()} 表达（null = 不能）。
     * 走到这里被拒的只有并发点两次和绕过界面的请求，所以三种拒因合成一个 400 ——
     * 商家侧的下一步在三种情况下都是同一个：联系平台。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @PostMapping("/biz/plan/trial")
    public MinePlanVO startTrial() {
        return planService.startTrial(BizContext.requireMerchantNo());
    }
}
