package ai.neargo.shop.marketing.visit.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.marketing.visit.StoreVisitService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 门店获客看板（P-10.1.4）。
 *
 * <p>漏斗四段「扫码 → 进店 → 注册 → 首单」。后三段一直有数据（归因逐条留痕 +
 * 首单回填），第一段是本批补的埋点 —— 在此之前 {@code /ops/stores/acquisition}
 * 整条是 mock，页面有数、点得动、不报错，而后端一行都没有。
 *
 * <p><b>路径落在 {@code /ops/stores/**} 但控制器不在 shop-merchant</b>：
 * 聚合读的是 {@code mkt_store_visit} 与 {@code mkt_attribution_log}，两张都在本模块；
 * 而 shop-core 与 shop-merchant 是兄弟模块，互相够不着。
 * 与 {@code OpsStoreController.storeDetail(/ops/stores/{storeNo})} 不冲突：
 * 精确路径优先于路径变量，Spring 先匹配 {@code acquisition}。
 *
 * <p>权限用 {@code store:page:audit} 而不是 ops-web 菜单上写的 {@code store:page:read} ——
 * 后者在 {@code UI_PERM_MAP} 里是 UNIMPLEMENTED（没有对应后端码）。
 * 审店招的与看获客的是同一拨人，复用它就不必新增权限码、也就不必发权限种子迁移。
 */
@Profile("ops")
@RestController
@Validated
public class OpsStoreAcquisitionController {

    /** 不给区间时的默认窗口。 */
    private static final long DEFAULT_WINDOW_MS = 30L * 24 * 3600 * 1000;

    private final StoreVisitService visitService;

    public OpsStoreAcquisitionController(StoreVisitService visitService) {
        this.visitService = visitService;
    }

    /**
     * 获客漏斗，按主体聚合。
     *
     * <p><b>不给区间不等于「有史以来」</b>：那个数只会越来越大且不能用于判断趋势。
     * 缺省取最近 30 天 —— 与其返回一个没人知道口径的累计值，不如给一个明确的窗口。
     *
     * @param from 起（毫秒时间戳，含）；不传取 {@code to - 30 天}
     * @param to   止（毫秒时间戳，含）；不传取此刻
     */
    @GetMapping("/ops/stores/acquisition")
    @PreAuthorize("@perm.can('" + Perms.STORE_PAGE_AUDIT + "')")
    public PageData<StoreVisitService.AcquisitionRow> acquisition(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        long end = to == null ? System.currentTimeMillis() : to;
        long start = from == null ? end - DEFAULT_WINDOW_MS : from;
        return visitService.acquisition(start, end, keyword, page, Math.min(size, 100));
    }
}
