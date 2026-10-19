package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.BizUsageQuery;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.DetailQuery;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.EntitlementDetail;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.UsageItem;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.AssetKind;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.member.CustomerIdentityPort.ReferenceQuery;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Merchant scope is the intersection of store membership and this exact read permission on each store. */
@Profile("api")
@RestController
public class BizEntitlementQueryController {
    private final EntitlementQueryPort queries;
    private final CustomerIdentityPort customers;
    public BizEntitlementQueryController(EntitlementQueryPort queries, CustomerIdentityPort customers) {
        this.queries = queries; this.customers = customers;
    }
    @PreAuthorize("@perm.canBiz('" + BizPerms.ENT_READ + "')")
    @GetMapping("/biz/entitlement-usage")
    public PageData<UsageItem> usage(@RequestParam(required = false) String storeNo,
            @RequestParam(required = false) AssetKind assetKind, @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        var ctx = context();
        return queries.merchantUsage(ctx, new BizUsageQuery(BizContext.requireMerchantNo(), allowed(), storeNo,
                assetKind, from, to, page, size));
    }
    @PreAuthorize("@perm.canBiz('" + BizPerms.ENT_READ + "')")
    @GetMapping("/biz/entitlements/{instanceNo}")
    public EntitlementDetail detail(@PathVariable String instanceNo, @RequestParam String customerRef,
                                    @RequestParam String storeNo) {
        var ctx = context(); var permitted = allowed();
        if (permitted != null && !permitted.contains(storeNo)) throw BizException.of(ErrorCode.NOT_FOUND);
        String entity = BizContext.requireMerchantNo();
        var customer = customers.resolveReference(ctx, new ReferenceQuery(entity, storeNo, customerRef));
        return queries.detail(ctx, new DetailQuery(customer.userNo(), entity, instanceNo, storeNo));
    }
    private EntitlementContext context() {
        var user = SecurityUtils.requireUser();
        if (user.realm() != Realm.MERCHANT) throw BizException.of(ErrorCode.FORBIDDEN);
        return new EntitlementContext(user.tenantNo(), user.userNo(), "merchant-entitlement-read", "0".repeat(64));
    }
    private Set<String> allowed() {
        var ctx = BizContext.current(); var stores = ctx.allowedStoresOrAll();
        return stores == null ? null : stores.stream().filter(s -> ctx.withStore(s).can(BizPerms.ENT_READ))
                .collect(Collectors.toUnmodifiableSet());
    }
}
