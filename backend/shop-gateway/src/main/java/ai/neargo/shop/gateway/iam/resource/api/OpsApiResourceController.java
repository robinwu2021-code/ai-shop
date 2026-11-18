package ai.neargo.shop.gateway.iam.resource.api;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.gateway.iam.resource.ApiResourceAdmin;
import ai.neargo.shop.gateway.iam.resource.ApiResourceAdmin.ApiResourceVO;
import ai.neargo.shop.gateway.iam.resource.ApiResourceAdmin.SummaryVO;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营端 · 接口资源（TDD-网关层与统一权限 §2.23；页面 {@code /system?tab=apiResource}）。
 *
 * <p>看用 {@code system:param:read}；停用与恢复用单独的 {@code system:api-resource:update}，只有超管能到。
 */
@Profile("ops")
@RestController
public class OpsApiResourceController {

    private final ApiResourceAdmin admin;

    public OpsApiResourceController(ApiResourceAdmin admin) {
        this.admin = admin;
    }

    /** 分页列表；筛选项都可以不传。 */
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_PARAM_READ + "')")
    @GetMapping("/ops/api-resources")
    public PageData<ApiResourceVO> list(@RequestParam(required = false) String endCode,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String source,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(defaultValue = "1") long page,
                                        @RequestParam(defaultValue = "20") long size) {
        return admin.page(endCode, keyword, source, status, page, size);
    }

    /** 各类条数，以及当前部署的提前判权档位与转发开关。 */
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_PARAM_READ + "')")
    @GetMapping("/ops/api-resources/summary")
    public SummaryVO summary() {
        return admin.summary();
    }

    /** 停用或恢复一条链接；返回改后的那一行。 */
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_API_RESOURCE_UPDATE + "')")
    @PostMapping("/ops/api-resources/{id}/status")
    public ApiResourceVO setStatus(@PathVariable long id, @RequestBody StatusReq req) {
        return admin.setStatus(id, req.status(), SecurityUtils.currentUserNo());
    }

    /** @param status ENABLED 或 DISABLED */
    public record StatusReq(String status) {
    }
}
