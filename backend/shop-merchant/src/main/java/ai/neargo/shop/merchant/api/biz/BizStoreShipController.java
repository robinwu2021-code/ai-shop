package ai.neargo.shop.merchant.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.merchant.service.StoreShipSettingService;
import ai.neargo.shop.merchant.service.StoreShipSettingService.ShipSettingCmd;
import ai.neargo.shop.merchant.service.StoreShipSettingService.ShipSettingVO;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 门店发货设置（TDD-快递100商家寄件 §7 AC12）。读写权限与收款设置同一档：
 * 看是 {@code biz:store}，改是 {@code biz:store:admin} —— 寄件地址改错了，快递员就去了别处。
 */
@Profile("api")
@RestController
public class BizStoreShipController {

    private final StoreShipSettingService service;

    public BizStoreShipController(StoreShipSettingService service) {
        this.service = service;
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @GetMapping("/biz/store/{storeNo}/ship-setting")
    public ShipSettingVO get(@PathVariable String storeNo) {
        return service.get(BizContext.requireMerchantNo(), storeNo);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @PutMapping("/biz/store/{storeNo}/ship-setting")
    public ShipSettingVO save(@PathVariable String storeNo, @RequestBody ShipSettingCmd req) {
        return service.save(BizContext.requireMerchantNo(), storeNo, req);
    }
}
