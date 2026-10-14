package ai.neargo.shop.merchant.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.merchant.service.StorePaySettingService;
import ai.neargo.shop.merchant.service.StorePaySettingService.PaySettingVO;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家端 · 门店收款方式（TDD-线下收款商家开关）。
 *
 * <p>读用 {@code biz:store}；改用 {@code biz:store:admin} —— 收不收现金、敢不敢货到付款，
 * 是店主级的经营决定（拒收、跑单的损失全在商家），与库存同步开关同一档。
 */
@Profile("api")
@RestController
public class BizStorePayController {

    private final StorePaySettingService service;

    public BizStorePayController(StorePaySettingService service) {
        this.service = service;
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @GetMapping("/biz/store/{storeNo}/pay-setting")
    public PaySettingVO get(@PathVariable String storeNo) {
        return service.get(BizContext.requireMerchantNo(), storeNo);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @PutMapping("/biz/store/{storeNo}/pay-setting")
    public PaySettingVO save(@PathVariable String storeNo, @RequestBody PaySettingReq req) {
        return service.save(BizContext.requireMerchantNo(), storeNo, req.offlinePayEnabled(), req.codEnabled());
    }

    /** 两个字段都是「null = 不改」 */
    public record PaySettingReq(Boolean offlinePayEnabled, Boolean codEnabled) {
    }
}
