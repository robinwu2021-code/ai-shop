package ai.neargo.shop.portal.ops.pay;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.payclient.PointsPolicyAppService;
import ai.neargo.shop.pay.PointsService.ClientPointsPolicy;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 端积分策略（哪些端不发分 / 不给核销）。
 *
 * <p><b>这个 controller 只做 HTTP 的事</b>：路径、权限注解、参数绑定、取当前操作人。
 * 校验、读写设置、留痕都在 {@link PointsPolicyAppService} 里 ——
 * 那是支付域拆分后 controller 与领域之间的那一层。
 *
 * <p>此前它自己拿着 {@code ObjectMapper} 解析 JSON、自己校验端名、自己记审计，
 * 而那些在支付域里时没人管得着（既有的
 * {@code ArchitectureTest#controllersMustNotTouchMappers} 只盯 {@code portal..} 包）。
 * <b>搬到主应用侧的第一分钟它就红了</b> —— 搬家没制造这个问题，只是让它被看见。
 */
@Profile("ops")
@RestController
@Validated
public class OpsPointsPolicyController {

    private final PointsPolicyAppService policyService;

    public OpsPointsPolicyController(PointsPolicyAppService policyService) {
        this.policyService = policyService;
    }

    @GetMapping("/ops/points/client-policy")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public ClientPointsPolicy policy() {
        return policyService.policy();
    }

    @PostMapping("/ops/points/client-policy")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_EXECUTE + "')")
    public ClientPointsPolicy save(@RequestBody ClientPointsPolicy req) {
        return policyService.save(req, SecurityUtils.currentUserNo());
    }
}
