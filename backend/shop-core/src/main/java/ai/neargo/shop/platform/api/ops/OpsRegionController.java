package ai.neargo.shop.platform.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.platform.RegionService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台端 · 行政区划浏览（ADR-013 阶段一）。
 *
 * <p><b>逐级查，不给整棵树</b>：四级共 44703 行、1.6 MB。运营挑一个街道只需要
 * 沿着「省 → 市 → 区 → 街道」走四次，每次几十条；给整棵树的话每开一次页面
 * 都要传一遍全国，而其中 99.9% 用不到。
 */
@Profile("ops")
@RestController
@Validated
public class OpsRegionController {

    private final RegionService regionService;

    public OpsRegionController(RegionService regionService) {
        this.regionService = regionService;
    }

    /**
     * 某区划的直接下级。{@code parent} 为空取省级。
     *
     * <p>{@code enabledOnly=false}（默认）给全量 —— 这是**运营维护面**，
     * 停用过的区划必须看得见，否则再也开不回来。与行业、授权码那两处同一条规矩。
     */
    @GetMapping("/ops/regions")
    @PreAuthorize("@perm.can('" + Perms.COMMUNITY_REGION_READ + "')")
    public List<RegionService.RegionVO> children(
            @RequestParam(required = false) String parent,
            @RequestParam(defaultValue = "false") boolean enabledOnly) {
        return regionService.children(parent, enabledOnly);
    }

    /** 从省到自身的整条链路。给选择器回显用 —— 端上不该自己按码长切片。 */
    @GetMapping("/ops/regions/path")
    @PreAuthorize("@perm.can('" + Perms.COMMUNITY_REGION_READ + "')")
    public List<RegionService.RegionVO> path(@RequestParam String code) {
        return regionService.path(code);
    }
}
