package ai.neargo.shop.portal.mp;

import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.dto.AuthorizationCommand;
import ai.neargo.shop.trade.dto.AuthorizationView;
import ai.neargo.shop.trade.service.EntitlementAuthorizationService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Confirms only a previously staged server-side quote for the authenticated consumer. */
@Profile("api")
@Validated
@RestController
public class MpEntitlementAuthorizationController {
    private final EntitlementAuthorizationService authorizations;

    public MpEntitlementAuthorizationController(EntitlementAuthorizationService authorizations) {
        this.authorizations = authorizations;
    }

    @PostMapping("/mp/orders/{orderNo}/entitlement-authorizations")
    public AuthorizationView confirm(@PathVariable String orderNo, @Valid @RequestBody AuthorizationCommand command) {
        var user = SecurityUtils.requireUser();
        if (user.realm() != Realm.CONSUMER) throw BizException.of(ErrorCode.FORBIDDEN);
        return authorizations.confirm(user.tenantNo(), user.userNo(), orderNo, command);
    }
}
