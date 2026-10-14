package ai.neargo.shop.platform.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.platform.RegionService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    /**
     * 新增一个区划节点（人工维护）。
     *
     * <p>官方数据停更后，真实发生的区划调整只能靠运营手工补。
     * 层级由父级推导，生成码带字母段 —— 与官方纯数字码永不冲突。
     */
    @PostMapping("/ops/regions")
    @PreAuthorize("@perm.can('" + Perms.COMMUNITY_REGION_UPDATE + "')")
    public RegionService.RegionVO create(@RequestBody NodeReq req) {
        return regionService.createNode(req.parent(), req.name(), SecurityUtils.currentUserNo());
    }

    /**
     * 停用 / 启用。停用只影响新选择，存量商家的经营范围不动 ——
     * 与行业停用同一口径。<b>不级联</b>：一次误操作不该波及几十个街道。
     */
    @PostMapping("/ops/regions/{code}/toggle")
    @PreAuthorize("@perm.can('" + Perms.COMMUNITY_REGION_UPDATE + "')")
    public RegionService.RegionVO toggle(@PathVariable String code, @RequestBody ToggleReq req) {
        return regionService.toggleNode(code, Boolean.TRUE.equals(req.enabled()),
                SecurityUtils.currentUserNo());
    }

    /** 改名。撤并更名真实发生；改名不动码，存量引用不受影响 */
    @PostMapping("/ops/regions/{code}/rename")
    @PreAuthorize("@perm.can('" + Perms.COMMUNITY_REGION_UPDATE + "')")
    public RegionService.RegionVO rename(@PathVariable String code, @RequestBody NodeReq req) {
        return regionService.renameNode(code, req.name(), SecurityUtils.currentUserNo());
    }

    public record NodeReq(String parent, String name) {
    }

    public record ToggleReq(Boolean enabled) {
    }
}