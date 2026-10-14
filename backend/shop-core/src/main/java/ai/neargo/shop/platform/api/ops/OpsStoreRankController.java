package ai.neargo.shop.platform.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.platform.DashboardService;
import ai.neargo.shop.platform.DashboardService.StoreRankRowVO;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台端 · 门店经营排行（门店③）。
 *
 * <p>经营看板早就有商家排行，<b>没有门店维度</b>。而多门店商家的货、单、码
 * 都挂在门店上：商家排行会把「一家很好、一家半死」平均成「还行」，
 * 那家半死的店在商家维度上永远看不见 —— 而它才是要去看的那家。
 *
 * <p>与 {@code /ops/stores/&#123;no&#125;/stats} 不重复：那一个答
 * 「这家店最近怎么样」（要先知道看哪家），这个答「哪几家最该看」。
 */
@Profile("ops")
@RestController
public class OpsStoreRankController {

    private final DashboardService dashboard;

    public OpsStoreRankController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @PreAuthorize("@perm.can('" + Perms.DASHBOARD_OVERVIEW_READ + "')")
    @GetMapping("/ops/dashboard/stores")
    public List<StoreRankRowVO> storeRanking(@RequestParam(defaultValue = "30") int days,
                                             @RequestParam(defaultValue = "20") int limit) {
        return dashboard.storeRanking(days, limit);
    }
}
