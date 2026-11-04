package ai.neargo.commerce.core.mall;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.mall.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.catalog.*;
import ai.neargo.commerce.core.spi.*;

import java.util.*;

/**
 * Published-only customer projection, independently authorized from operator catalog management.
 */
public final class DefaultStorefrontFacade implements StorefrontFacade {
    private final CatalogRepository catalog;
    private final MallRepository mall;
    private final SubscriptionAccountRepository accounts;
    private final CommerceUnitOfWork uow;
    private final StoreDirectoryPort stores;
    private final ActorAuthorizationPort auth;
    private final CheckoutReadinessPort readiness;

    public DefaultStorefrontFacade(
            CatalogRepository catalog,
            MallRepository mall,
            SubscriptionAccountRepository accounts,
            CommerceUnitOfWork uow,
            StoreDirectoryPort stores,
            ActorAuthorizationPort auth,
            CheckoutReadinessPort readiness) {
        this.catalog = catalog;
        this.mall = mall;
        this.accounts = accounts;
        this.uow = uow;
        this.stores = stores;
        this.auth = auth;
        this.readiness = Objects.requireNonNull(readiness);
    }

    public StorefrontView catalog(ActorContext actor, StoreRef ref) {
        if (actor == null
                || ref == null
                || actor.subjectKind() != SubjectKind.ACCOUNT
                || !Objects.equals(actor.tenantNo(), ref.tenantNo())
                || actor.principalNo() == null
                || actor.principalNo().isBlank()
                || actor.accountSessionFingerprint() == null
                || actor.accountSessionFingerprint().isBlank())
            throw new MallException("FORBIDDEN");
        return uow.required(
                () -> {
                    var permission = auth.authorize(actor, ref, "commerce:mall:browse");
                    if (permission == null || !permission.allowed())
                        throw new MallException("FORBIDDEN");
                    var store =
                            stores.find(ref)
                                    .filter(v -> ref.equals(v.store()))
                                    .orElseThrow(() -> new MallException("NOT_FOUND"));
                    if (store.model() != CommerceModel.STORE_V1
                            || store.status() != StoreStatus.ACTIVE)
                        throw new MallException("STORE_UNAVAILABLE");
                    var account = accounts.lockSubscriptionAccount(ref);
                    if (!ref.equals(account.store())
                            || !store.merchantNo().equals(account.entityNo())
                            || !store.businessTypeCode().equals(account.businessTypeCode()))
                        throw new MallException("VERSION_CONFLICT");
                    String currency = mall.storeCurrency(ref);
                    var state = catalog.load(actor.tenantNo(), CatalogLockMode.SHARED);
                    var type = state.types.get(store.businessTypeCode());
                    var purchase = auth.authorize(actor, ref, "commerce:mall:purchase");
                    boolean canProcure = purchase != null && purchase.allowed();
                    var products = new ArrayList<StorefrontView.Product>();
                    if (type != null && type.draft().enabled() && type.catalogVersion() > 0) {
                        for (var product : state.products.values()) {
                            var p = product.draft();
                            if (!"ON_SALE".equals(product.saleStatus())
                                    || !p.allowedBusinessTypes().contains(store.businessTypeCode()))
                                continue;
                            var skus = new ArrayList<StorefrontView.Sku>();
                            for (var master : state.skus.values()) {
                                if (!p.productNo().equals(master.productNo())
                                        || !"ON_SALE".equals(master.saleStatus())) continue;
                                var version = state.skuVersions.get(master.currentVersionNo());
                                if (version == null || !"PUBLISHED".equals(version.status()))
                                    continue;
                                var sku = version.draft();
                                if (!master.skuNo().equals(sku.skuNo())
                                        || !p.productNo().equals(sku.productNo())
                                        || !sku.allowedBusinessTypes()
                                                .contains(store.businessTypeCode())
                                        || !currency.equals(sku.price().currency())) continue;
                                var status = readiness.evaluate(store, sku.price(), List.of(sku));
                                if (status == null)
                                    status =
                                            new CheckoutReadinessPort.Readiness(
                                                    false, List.of("CHECK_UNAVAILABLE"), List.of());
                                if (!canProcure)
                                    status =
                                            new CheckoutReadinessPort.Readiness(
                                                    false,
                                                    List.of("PROCUREMENT_REQUIRED"),
                                                    status.paymentOptions());
                                skus.add(new StorefrontView.Sku(sku, status));
                            }
                            if (!skus.isEmpty())
                                products.add(new StorefrontView.Product(p, List.copyOf(skus)));
                        }
                    }
                    return new StorefrontView(
                            ref,
                            store.businessTypeCode(),
                            currency,
                            type == null ? 0 : type.catalogVersion(),
                            canProcure,
                            List.copyOf(products));
                });
    }
}
