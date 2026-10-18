package ai.neargo.shop.paybridge;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.promotion.service.EntitlementScopeValidationService;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import org.springframework.stereotype.Component;

/** Merchant adapters derive tenant, actor and entity from validated sessions; public input supplies only scope refs. */
@Component
public class EntitlementScopeResolver {
    private final EntitlementScopeValidationService validation;

    public EntitlementScopeResolver(EntitlementScopeValidationService validation) {
        this.validation = validation;
    }

    public CatalogScope resolve(RedemptionScope scope) {
        var user = SecurityUtils.requireUser();
        if (user.realm() != Realm.MERCHANT) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        String entityNo = BizContext.requireMerchantNo();
        var context = new CatalogScope(user.tenantNo(), entityNo, user.userNo(), false);
        validation.validate(context.tenantNo(), context.entityNo(), scope);
        var allowed = BizContext.current().allowedStoresOrAll();
        if (allowed != null) {
            if (!"STORES".equals(scope.mode())) {
                throw BizException.of(ErrorCode.FORBIDDEN);
            }
            if (!allowed.containsAll(scope.refs())) {
                throw BizException.of(ErrorCode.NOT_FOUND);
            }
        }
        return context;
    }

    public CatalogScope resolveDefinition(EntitlementDefinition definition) {
        if (definition == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var context = resolve(definition.redemptionScope());
        validation.validateDefinition(context.tenantNo(), context.entityNo(), definition);
        return context;
    }
}
