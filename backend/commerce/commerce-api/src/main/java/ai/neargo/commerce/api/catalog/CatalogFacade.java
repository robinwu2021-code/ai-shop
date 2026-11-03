package ai.neargo.commerce.api.catalog;

import ai.neargo.commerce.api.common.ActorContext;

public interface CatalogFacade {
    DraftSaveResult saveBusinessType(ActorContext actor, BusinessTypeDraft command);

    DraftSaveResult saveBusinessEnd(ActorContext actor, BusinessEndDraft command);

    DraftSaveResult savePlan(ActorContext actor, PlanDraft command);

    DraftSaveResult saveProduct(ActorContext actor, ProductDraft command);

    DraftSaveResult saveSku(ActorContext actor, SkuDraft command);

    PublicationResult publishIndustry(ActorContext actor, IndustryPublicationCommand command);

    PublicationResult publishSku(ActorContext actor, SkuPublicationCommand command);

    DraftSaveResult stopSale(ActorContext actor, StopSaleCommand command);

    CatalogView catalog(ActorContext actor, String typeCode);
}
