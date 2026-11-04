package ai.neargo.commerce.core.catalog;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.catalog.business.BusinessTypeService;
import ai.neargo.commerce.core.catalog.end.BusinessEndService;
import ai.neargo.commerce.core.catalog.plan.IndustryPublicationService;
import ai.neargo.commerce.core.catalog.product.SkuPublicationService;
import ai.neargo.commerce.core.spi.*;

import java.time.Clock;
import java.util.*;

public final class DefaultCatalogFacade implements CatalogFacade {
    private final CatalogQueryService queries;
    private final BusinessTypeService types;
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
        var support = new CatalogSupport(r, u, a, readiness, events, clock, ids);
        queries = new CatalogQueryService(support);
        types = new BusinessTypeService(support);
        ends = new BusinessEndService(support);
        skus = new SkuPublicationService(support);
        industries = new IndustryPublicationService(support, skus);
    }

    public DraftSaveResult saveBusinessType(ActorContext a, BusinessTypeDraft d) {
        return types.save(a, d);
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

    public CatalogOverview overview(ActorContext actor) { return queries.overview(actor); }

    public CatalogView catalog(ActorContext actor, String typeCode) {
        return queries.catalog(actor, typeCode);
    }
}
