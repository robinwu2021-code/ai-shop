package ai.neargo.shop.portal.biz;

import ai.neargo.shop.platform.RegionService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * B 端 · 行政区划浏览（ADR-013 阶段二）。
 *
 * <p>商家框经营范围时要能逐级挑到街道。与运营端那套是同一个 Service，
 * <b>只差一个口径</b>：这里恒定 {@code enabledOnly=true} ——
 * 停用的区划是运营的维护对象，不该出现在商家的选择器里。
 * 让商家看见一个自己选不了（或选了会被驳回）的选项，只会让他反复来问。
 *
 * <p>放在 {@code api} profile 而不是 {@code ops}：这是商家侧的路由，
 * 以 ops 起服务时它就该 404 —— 隔离靠路由不存在，不靠安全链。
 */
@Profile("api")
@RestController
public class BizRegionController {

    private final RegionService regionService;

    public BizRegionController(RegionService regionService) {
        this.regionService = regionService;
    }

    /** 某区划的直接下级。{@code parent} 为空取省级。 */
    @GetMapping("/biz/regions")
    public List<RegionService.RegionVO> children(@RequestParam(required = false) String parent) {
        return regionService.children(parent, true);
    }
}
