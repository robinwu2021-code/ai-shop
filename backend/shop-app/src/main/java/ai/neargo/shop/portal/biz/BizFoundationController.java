package ai.neargo.shop.portal.biz;

import ai.neargo.commerce.api.common.StoreRef;
import ai.neargo.commerce.api.foundation.FoundationQueryFacade;
import ai.neargo.commerce.api.foundation.FoundationQueryFacade.*;
import ai.neargo.shop.commercebridge.ShopCommerceActorAdapter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Profile("api")
@ConditionalOnProperty(name = "commerce.enabled", havingValue = "true")
public class BizFoundationController {
    private final FoundationQueryFacade queries;

    public BizFoundationController(FoundationQueryFacade queries) {
        this.queries = queries;
    }

    @PreAuthorize("@perm.merchantAccount()")
    @GetMapping("/biz/business-types")
    public List<BusinessType> types() {
        return queries.businessTypes(ShopCommerceActorAdapter.current());
    }

    @PreAuthorize("@perm.merchantAccount()")
    @GetMapping("/biz/business-types/{typeCode}/plans")
    public PlanCatalog plans(@PathVariable String typeCode) {
        return queries.plans(ShopCommerceActorAdapter.current(), typeCode);
    }

    @PreAuthorize("@perm.memberOfSelectedStore(#storeNo)")
    @GetMapping("/biz/stores/{storeNo}/context")
    public StoreContext context(@PathVariable String storeNo) {
        var actor = ShopCommerceActorAdapter.current();
        return queries.store(actor, new StoreRef(actor.tenantNo(), storeNo));
    }

    @PreAuthorize("@perm.memberOfSelectedStore(#storeNo)")
    @GetMapping("/biz/stores/{storeNo}/subscription")
    public Subscription subscription(@PathVariable String storeNo) {
        var actor = ShopCommerceActorAdapter.current();
        return queries.subscription(actor, new StoreRef(actor.tenantNo(), storeNo));
    }

    @PreAuthorize("@perm.memberOfSelectedStore(#storeNo)")
    @GetMapping("/biz/stores/{storeNo}/entitlements")
    public Entitlements entitlements(@PathVariable String storeNo) {
        var actor = ShopCommerceActorAdapter.current();
        return queries.entitlements(actor, new StoreRef(actor.tenantNo(), storeNo));
    }
}
