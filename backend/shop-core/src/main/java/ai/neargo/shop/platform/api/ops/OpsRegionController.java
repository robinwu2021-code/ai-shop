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
     * 商家补录的村级队列。
     *
     * <p>官方村级数据停在 2023-06-30（统计局已停发），之后新增的村只能靠商家补录。
     * 补录的先只对提报方可见，运营在这里确认后才转为全平台共享。
     *
     * @param status 默认 PENDING；传 REJECTED 可回看驳回过的
     */
    @GetMapping("/ops/regions/pending")
    @PreAuthorize("@perm.can('" + Perms.COMMUNITY_REGION_READ + "')")
    public List<RegionService.PendingVO> pending(
            @RequestParam(required = false) String status) {
        return regionService.pendingVillages(status);
    }

    /**
     * 裁决一条补录。
     *
     * <p><b>要 update 权限而不是 read</b>：通过一条会让它对全平台商家可见，
     * 而读区划几乎人人都有 —— 两者的出错后果不在一个量级。
     */
    @PostMapping("/ops/regions/{code}/confirm")
    @PreAuthorize("@perm.can('" + Perms.COMMUNITY_REGION_UPDATE + "')")
    public void confirm(@PathVariable String code, @RequestBody ConfirmReq req) {
        regionService.confirmVillage(code, Boolean.TRUE.equals(req.pass()), req.reason(),
                SecurityUtils.currentUserNo());
    }

    /** @param reason 驳回原因，驳回时必填 —— 原样回给商家，不写的话他只会原样再提一次 */
    public record ConfirmReq(Boolean pass, String reason) {
    }
}
