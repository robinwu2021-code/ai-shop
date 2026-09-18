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


}
