package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.merchant.service.StoreFulfillmentService;
import ai.neargo.shop.merchant.service.StoreFulfillmentService.PickupRef;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 开团可选的自提点（原型 s34 第三行）。
 *
 * <p>就是门店「自提点送货」那一路已经引用、且已核实（ACTIVE）的点 —— 团按自提点成团，
 * 货送不到的点开了团也履约不了。
 *
 * <p>单开一个端点而不是让开团页去读 {@code /biz/stores/{storeNo}/fulfillment}：
 * 那个要门店权限，而开团是营销权限。只有营销权限的店员会看到一个空的下拉框，还不报错。
 * 放在 portal 层：它把营销（开团）与商家（门店送货方式）两个域拼在一起。
 */
@Profile("api")
@RestController
public class BizGroupPickupController {

    private final StoreFulfillmentService fulfillmentService;

    public BizGroupPickupController(StoreFulfillmentService fulfillmentService) {
        this.fulfillmentService = fulfillmentService;
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @GetMapping("/biz/group/pickups")
    public List<PickupRef> pickups() {
        return fulfillmentService.get(BizContext.requireMerchantNo(), null).channels().stream()
                .filter(c -> Fulfillments.NEIGHBOR_PICKUP.equals(c.channel()) && c.enabled())
                .flatMap(c -> c.pickups() == null ? java.util.stream.Stream.<PickupRef>empty() : c.pickups().stream())
                .filter(p -> "ACTIVE".equals(p.status()))
                .toList();
    }
}
