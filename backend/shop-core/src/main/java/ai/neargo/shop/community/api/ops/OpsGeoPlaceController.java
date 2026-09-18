package ai.neargo.shop.community.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.community.service.CommunityAdminService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 固定地址库。
 *
 * <p><b>与社区分开一个控制器</b>：它们是两类东西。聚落是业务对象
 * （围栏、商品池、开没开通，运营手动维护）；这张表只回答「这个坐标叫什么」，
 * 由系统自己长出来。放在一起的话，那个控制器就同时装着两种生命周期完全不同的资源 ——
 * 控制器内聚闸门当场点了名。
 *
 * <p>权限仍判 {@code COMMUNITY_*}：它干的事是「看/改社区主数据的上游」，
 * 与开城、调围栏同一档。为它单开一个权限码只会让权限表多一行而没有任何人真的分开授。
 */
@Profile("ops")
@RestController
@Validated
public class OpsGeoPlaceController {

    private final CommunityAdminService adminService;
    private final AuditLogPort auditLogPort;

    public OpsGeoPlaceController(CommunityAdminService adminService, AuditLogPort auditLogPort) {
        this.adminService = adminService;
        this.auditLogPort = auditLogPort;
    }

    /**
     * 固定地址库这一屏。
     *
     * <p><b>它回答的是「我们还要依赖地图多久」</b>：库里有多少地方、其中多少是
     * 建筑级、被命中过多少次。{@code mapStatus} 那一行更要紧 —— 熔断与额度是
     * 进程内状态，端上只看得到「地名标没标陈旧」，**这儿是唯一能提前发现
     * 「地图快不行了」的地方**。
     */
    @GetMapping("/ops/geo/places")
    @PreAuthorize("@perm.can('" + Perms.COMMUNITY_READ + "')")
    public CommunityAdminService.PlacePageVO places(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) Integer minHits,
            @RequestParam(required = false, defaultValue = "200") int limit) {
        return adminService.places(kind, minHits, limit);
    }

    /**
     * 把高频建筑沉淀成聚落 —— **「逐步完善到系统中」那件事的落点**。
     *
     * <p>升级之后这些地方走的是聚落那条更靠前的路：从此不再依赖地图，
     * 也就不怕额度、不怕对方挂掉。建成 CLOSED，放出来仍是单独一步
     * （理由与 import-estates 同：范围没铺开就 OPEN，买家会看到空货架）。
     *
     * <p>{@code dryRun} **默认 true**：一次动几百行的接口，默认值要在安全那一边。
     */
    @PostMapping("/ops/geo/places/promote")
    @PreAuthorize("@perm.can('" + Perms.COMMUNITY_UPDATE + "')")
    public CommunityAdminService.ImportResult promotePlaces(@RequestBody PromotePlacesReq req) {
        var r = adminService.promotePlaces(req.regionCode(),
                req.minHits() == null ? 10 : req.minHits(),
                !Boolean.FALSE.equals(req.dryRun()), SecurityUtils.currentUserNo());
        // 试算不留痕：留了的话审计日志里全是「沉淀了 0 条」，真正那一次反而淹在里面
        if (!r.dryRun()) {
            auditLogPort.record("GEO_PLACE_PROMOTE", req.regionCode(),
                    "新建 " + r.created() + " 更新 " + r.updated() + " 跳过 " + r.skipped());
        }
        return r;
    }

    /** @param minHits 命中次数门槛，默认 10 —— 路过一两次的地方不值得建成聚落 */
    public record PromotePlacesReq(String regionCode, Integer minHits, Boolean dryRun) {
    }
}
