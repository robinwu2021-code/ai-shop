package ai.neargo.shop.platform.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.platform.AppVersionService;
import ai.neargo.shop.platform.dto.AppVersionVOs.OpsAppVersionVO;
import ai.neargo.shop.platform.dto.AppVersionVOs.SaveAppVersionReq;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营端 · App 版本策略（TDD-商户App问题回报与版本 V6；页面 {@code /system?tab=appVersion}）。
 *
 * <p><b>看与改是两个码</b>：看用 {@code system:param:read}（技术运维就有）；改用单独的
 * {@code system:app-version:update}（已拍板 P5）—— 抬高最低支持版会让低于它的所有旧包立刻停写，
 * 影响面是全部商户，不该跟着 {@code system:param:update} 一起发给技术运维。
 */
@Profile("ops")
@RestController
@Validated
public class OpsAppVersionController {

    private final AppVersionService appVersionService;

    public OpsAppVersionController(AppVersionService appVersionService) {
        this.appVersionService = appVersionService;
    }

    /** 每个平台一行；没配过的也列出来（revision=0）。 */
    @GetMapping("/ops/app-versions")
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_PARAM_READ + "')")
    public List<OpsAppVersionVO> list() {
        return appVersionService.list();
    }

    /** 改一行策略。revision 对不上 → 10409；每次改动写高危审计。 */
    @PutMapping("/ops/app-versions/{appCode}/{platform}")
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_APP_VERSION_UPDATE + "')")
    public OpsAppVersionVO save(@PathVariable String appCode, @PathVariable String platform,
                                @Valid @RequestBody SaveAppVersionReq req) {
        return appVersionService.save(appCode, platform, req, SecurityUtils.currentUserNo());
    }
}
