package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.dto.CardProductBindingView;
import ai.neargo.shop.product.dto.ProductEntitlementBinding;
import ai.neargo.shop.product.service.CardProductBindingService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Profile("api")
@RestController
public class BizCardProductBindingController {
    private final CardProductBindingService bindings;
    public BizCardProductBindingController(CardProductBindingService bindings) { this.bindings = bindings; }
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    @PostMapping("/biz/card-product-bindings")
    public CardProductBindingView bind(@RequestBody ProductEntitlementBinding command,
                                        @RequestHeader("Idempotency-Key") String key) {
        var user = SecurityUtils.requireUser();
        if (user.realm() != Realm.MERCHANT || BizContext.current().allowedStoresOrAll() != null) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var ctx = new EntitlementContext(user.tenantNo(), user.userNo(), key, "0".repeat(64));
        return bindings.bind(ctx, BizContext.requireMerchantNo(), command, key);
    }
}
