package ai.neargo.shop.commercebridge;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.BizIdentityResolver;
import ai.neargo.shop.auth.SecurityUtils;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.stream.Stream;

/** Binds the payment handler's actual target after MVC has decoded path, query and body. */
@Component
@Profile("api")
public class FoundationPaymentAccess {
    private final ShopCommerceStoreAdapter stores;
    private final BizIdentityResolver resolver;

    FoundationPaymentAccess(ShopCommerceStoreAdapter stores, BizIdentityResolver resolver) {
        this.stores = stores;
        this.resolver = resolver;
    }

    public FoundationTarget require(
            HttpServletRequest request, String entity, String operationStore) {
        var target = FoundationTarget.current().orElseGet(() -> legacyTarget(request, operationStore));
        target.checkRequest(request);
        target.check(entity, operationStore);
        return target;
    }

    private FoundationTarget legacyTarget(HttpServletRequest request, String operationStore) {
        // Old clients may name the branch in the operation without a selection header.
        String candidate = Stream.of(request.getHeader(BizContextFilter.STORE_HEADER),
                        operationStore, request.getParameter("storeNo"))
                .filter(value -> value != null && !value.isBlank())
                .findFirst().orElseGet(BizContext::requireStoreNo);
        return stores.legacyFinanceTarget(SecurityUtils.requireUser(), BizContext.requireMerchantNo(),
                candidate, resolver);
    }
}
