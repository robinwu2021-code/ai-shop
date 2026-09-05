package ai.neargo.shop.community.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.community.service.CommunityAdminService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.UserQueryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 覆盖面：坐标健康度与位置分布（P-2.1）。
 *
 * <p><b>为什么单开一个控制器</b>：这两条问的是「平台的覆盖长什么样」，
 * 与 {@code OpsCommunityController} 的「维护某一个聚落/自提点/区划」不是一个资源。
 * 塞在一起的话那个类会装四个资源，而「胖控制器」那道闸拦的正是这件事 ——
 * 它不是洁癖：一个类装四个资源，改其中一个的人要读完另外三个才敢动。
 *
 * <p>⚠️ 这条闸从 {@code /ops/coverage/health} 落地那天起就该红了，
 * 而它一直没跑 —— 这个分支从 2026-09-01 起没人推过，pre-push 也就没机会说话。
 * 闸门只在有人推的时候才是闸门。
 */
@Profile("ops")
@RestController
@Validated
public class OpsCoverageController {

    private final CommunityAdminService adminService;
    private final MerchantQueryPort merchantQueryPort;
    private final UserQueryPort userQueryPort;

    public OpsCoverageController(CommunityAdminService adminService,
                                 MerchantQueryPort merchantQueryPort,
                                 UserQueryPort userQueryPort) {
        this.adminService = adminService;
        this.merchantQueryPort = merchantQueryPort;
        this.userQueryPort = userQueryPort;
    }

    /**
     * 坐标健康度 —— <b>这是整个位置模块的分母。</b>
     *
     * <p>为什么必须有这一页：门店没标点时 {@code requireWithinDeliveryRadius}
     * 那条闸**直接放行**（缺数据不该拦正常订单，这是对的）。代价是商家以为自己
     * 限了三公里、实际多远的单都进来，等他要送货才发现送不到，那时钱已经收了。
     * 而这件事今天在任何界面上都看不见 —— 商家看不见、运营也看不见。
     *
     * <p>同理，没坐标的收货地址推不出任何聚落。它们既不算进任何一个片区，
     * 也不该被静默丢掉：位置分布那张表必须把它们**单列一格**，
     * 否则会把「缺数据」说成「缺需求」，而运营会据此去撤一个其实有人的片区的商家。
     *
     * <p><b>给明细不只给数字</b>：只说「7 家没标点」，运营下一步无从做起。
     */
    @GetMapping("/ops/coverage/health")
    @PreAuthorize("@perm.can('" + Perms.COMMUNITY_READ + "')")
    public CoverageHealthVO coverageHealth() {
        var stores = merchantQueryPort.storeCoordHealth();
        var addresses = userQueryPort.addressCoordHealth();
        var communities = adminService.communityCoordHealth();
        return new CoverageHealthVO(stores, addresses, communities);
    }

    public record CoverageHealthVO(MerchantQueryPort.StoreCoordHealth stores,
                                   UserQueryPort.AddressCoordHealth addresses,
                                   CommunityAdminService.CommunityCoordHealth communities) {
    }

    /**
     * 位置分布：聚落 × 买家 × 商家 × 商品。
     *
     * <p>返回体里 {@code unattributable} 与 {@code rows} <b>并列</b>，不是脚注 ——
     * 端上要把它画成同样显眼的一块，否则「缺数据」会被读成「缺需求」。
     */
    @GetMapping("/ops/coverage/distribution")
    @PreAuthorize("@perm.can('" + Perms.COMMUNITY_READ + "')")
    public CommunityAdminService.DistributionVO distribution() {
        return adminService.distribution();
    }
}
