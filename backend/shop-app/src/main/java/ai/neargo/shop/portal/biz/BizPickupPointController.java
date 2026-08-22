package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.community.service.CommunityService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.PickupQueryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 门店的取货点（P1，方案 v4 §4.1「自提」一路）。
 *
 * <p>两件事：挑系统里已有的点、自建一个。自建落 PENDING 待运营核实 ——
 * 地址要印在买家取货页上，假地址的信任成本由平台背。
 * 门店归属在这里按主体比对：别家门店号对本商家而言就是不存在（NOT_FOUND 不走 FORBIDDEN，
 * 403 会把「存在哪些门店号」泄给猜号的人）。
 */
@Profile("api")
@RestController
public class BizPickupPointController {

    private final CommunityService communityService;
    private final MerchantQueryPort merchantPort;
    private final PickupQueryPort pickupPort;

    public BizPickupPointController(CommunityService communityService, MerchantQueryPort merchantPort,
                                    PickupQueryPort pickupPort) {
        this.communityService = communityService;
        this.merchantPort = merchantPort;
        this.pickupPort = pickupPort;
    }

    /**
     * 候选点：主体经营范围内（可见性唯一出口 {@code reachableCommunities}）的常驻点 + 本店自建的点。
     * 范围为空时只剩本店自建的 —— 自提靠落点，没框范围本来也没有落点可言。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @GetMapping("/biz/pickup-points/candidates")
    public List<CommunityService.PickupCandidate> candidates(@RequestParam String storeNo) {
        String merchantNo = BizContext.requireMerchantNo();
        String store = requireOwnStore(merchantNo, storeNo);
        return communityService.pickupCandidates(merchantPort.reachableCommunities(merchantNo), store);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @PostMapping("/biz/pickup-points")
    public CommunityService.PickupCandidate selfBuild(@RequestBody SelfBuildReq req) {
        String merchantNo = BizContext.requireMerchantNo();
        String store = requireOwnStore(merchantNo, req.storeNo());
        /*
         * 归社区的退路：就近（服务内）→ 经营范围里的第一个 → 本店已有自提点所在的社区。
         * 存量社区大多没坐标、存量店常常还没框范围 —— 两级都空时第三级多半还在。
         */
        List<String> reachable = merchantPort.reachableCommunities(merchantNo);
        String fallback = reachable.isEmpty() ? null : reachable.get(0);
        if (fallback == null) {
            fallback = pickupPort.activeStorePickupNos(List.of(store)).stream()
                    .map(no -> pickupPort.find(no).map(PickupQueryPort.PickupBrief::communityNo).orElse(null))
                    .filter(c -> c != null && !c.isBlank())
                    .findFirst().orElse(null);
        }
        return communityService.selfBuildPickup(new CommunityService.SelfBuildCmd(
                store, req.name(), req.address(), req.latE6(), req.lngE6(), req.openHours(), req.communityNo(),
                fallback));
    }

    /** "default" = 默认门店，与送货方式端点同一约定 */
    private String requireOwnStore(String merchantNo, String storeNo) {
        List<String> mine = merchantPort.storeNos(merchantNo);
        if (storeNo == null || storeNo.isBlank() || "default".equals(storeNo)) {
            return merchantPort.defaultStoreNo(merchantNo)
                    .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        }
        if (!mine.contains(storeNo)) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return storeNo;
    }

    public record SelfBuildReq(String storeNo, String name, String address, Integer latE6, Integer lngE6,
                               String openHours, String communityNo) {
    }
}
