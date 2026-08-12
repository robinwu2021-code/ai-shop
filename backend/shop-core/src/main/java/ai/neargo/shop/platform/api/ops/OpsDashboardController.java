package ai.neargo.shop.platform.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.platform.DashboardService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台端 · 运营工作台（P-16.1）。
 *
 * <p>这三个端点此前<b>全是 404</b> —— ops-web 的契约、类型、页面早就写好了，
 * 缺的是后端，于是经营看板一直空着。与本轮反复撞到的「有能力没有消费方」相反：
 * <b>有消费方没有产出</b>。
 *
 * <p>权限用 {@code order:view} 而不是新造一个：看板上的数就是订单与售后的聚合，
 * 能看订单的人本来就该能看这些数；反过来，看不了订单的人看到 GMV 也没有意义。
 */
@Profile("ops")
@RestController
public class OpsDashboardController {

    /** 趋势的默认跨度。两周足够看出周内波动，又不至于把首屏拉慢 */
    private static final int DEFAULT_TREND_DAYS = 14;

    private final DashboardService dashboardService;

    public OpsDashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/ops/dashboard/kpi")
    @PreAuthorize("@perm.can('" + Perms.DASHBOARD_OVERVIEW_READ + "')")
    public DashboardService.KpiVO kpi() {
        return dashboardService.kpi();
    }

    @GetMapping("/ops/dashboard/trend")
    @PreAuthorize("@perm.can('" + Perms.DASHBOARD_OVERVIEW_READ + "')")
    public List<DashboardService.TrendPointVO> trend(
            @RequestParam(required = false) Integer days) {
        return dashboardService.trend(days == null ? DEFAULT_TREND_DAYS : days);
    }

    /**
     * 获客漏斗。
     *
     * <p><b>只返回有数据源的环节</b>：扫码与进店需要埋点，平台没有那两张事件表。
     * 返回 0 会被读成「一个人都没扫码」，而运营会照着它判断投放效果。
     */
    @GetMapping("/ops/dashboard/funnel")
    @PreAuthorize("@perm.can('" + Perms.DASHBOARD_OVERVIEW_READ + "')")
    public List<DashboardService.FunnelRowVO> funnel() {
        return dashboardService.funnel();
    }
}
