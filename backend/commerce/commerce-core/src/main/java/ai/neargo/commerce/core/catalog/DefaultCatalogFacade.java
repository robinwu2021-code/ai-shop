package ai.neargo.commerce.core.catalog;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.catalog.business.BusinessTypeService;
import ai.neargo.commerce.core.catalog.category.CategoryService;
import ai.neargo.commerce.core.catalog.end.BusinessEndService;
import ai.neargo.commerce.core.catalog.plan.IndustryPublicationService;
import ai.neargo.commerce.core.catalog.product.SkuPublicationService;
import ai.neargo.commerce.core.spi.*;

import java.time.Clock;
import java.util.*;

public final class DefaultCatalogFacade implements CatalogFacade {
    private final CatalogQueryService queries;
    private final CatalogSupport mallCatalog;
    private final BusinessTypeService types;
    private final CategoryService categories;
    private final BusinessEndService ends;
    private final IndustryPublicationService industries;
    private final SkuPublicationService skus;

    public DefaultCatalogFacade(
            CatalogRepository r,
            CommerceUnitOfWork u,
            CatalogAuthorizationPort a,
            BusinessEndReadinessPort readiness,
            CommerceEventPersistencePort events,
            Clock clock,
            CommerceIdGenerator ids) {
        this(r, u, a, readiness, events, clock, ids, MoneyPolicyPort.LEGACY);
    }

    public DefaultCatalogFacade(CatalogRepository r, CommerceUnitOfWork u, CatalogAuthorizationPort a,
            BusinessEndReadinessPort readiness, CommerceEventPersistencePort events, Clock clock,
            CommerceIdGenerator ids, MoneyPolicyPort moneyPolicies) {
        this(new CatalogSupport(r, u, a, readiness, events, clock, ids, moneyPolicies), null);
    }

    public DefaultCatalogFacade(CatalogSupport support, CatalogSupport mallCatalog) {
        this.mallCatalog = mallCatalog;
        var migrated = mallCatalog == null ? support : mallCatalog;
        queries = new CatalogQueryService(migrated);
        types = new BusinessTypeService(migrated, mallCatalog != null);
        categories = new CategoryService(migrated, mallCatalog == null);
        ends = new BusinessEndService(migrated);
        skus = new SkuPublicationService(migrated);
        industries = new IndustryPublicationService(migrated, skus);
    }

    public DraftSaveResult saveBusinessType(ActorContext a, BusinessTypeDraft d) {
        return types.save(a, d);
    }

    public DraftSaveResult saveCategory(ActorContext a, CategoryDraft d) {
        return categories.save(a, d);
    }

    public java.util.List<CategoryView> categories(ActorContext a) {
        return categories.list(a);
    }

    public DraftSaveResult saveBusinessEnd(ActorContext a, BusinessEndDraft d) {
        return ends.save(a, d);
    }

    public DraftSaveResult savePlan(ActorContext a, PlanDraft d) {
        return industries.save(a, d);
    }

    public DraftSaveResult saveProduct(ActorContext a, ProductDraft d) {
        return skus.saveProduct(a, d);
    }

    public DraftSaveResult saveSku(ActorContext a, SkuDraft d) {
        return skus.save(a, d);
    }

    public PublicationResult publishIndustry(ActorContext a, IndustryPublicationCommand d) {
        return industries.publish(a, d);
    }

    public PublicationResult publishSku(ActorContext a, SkuPublicationCommand d) {
        return skus.publish(a, d);
    }

    public DraftSaveResult stopSale(ActorContext a, StopSaleCommand d) {
        return skus.stop(a, d);
    }

    public java.util.List<BusinessEndDeploymentOption> deploymentOptions(ActorContext actor) { return queries.deploymentOptions(actor); }

    @Override
    public List<BusinessTypeView> businessTypes(ActorContext actor) {
        if (mallCatalog == null) return CatalogFacade.super.businessTypes(actor);
        mallCatalog.authorize(actor, "read");
        return mallCatalog.repository.businessTypes(actor.tenantNo()).stream()
                .map(t -> new BusinessTypeView(t.draft(), t.version(), t.catalogVersion())).toList();
    }

    public CatalogOverview overview(ActorContext actor) { return queries.overview(actor); }

    public CatalogView catalog(ActorContext actor, String typeCode) {
        return queries.catalog(actor, typeCode);
    }
}
