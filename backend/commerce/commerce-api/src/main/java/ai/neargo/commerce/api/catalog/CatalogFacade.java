package ai.neargo.commerce.api.catalog;

import ai.neargo.commerce.api.common.*;

public interface CatalogFacade {
 OperationResult saveBusinessType(ActorContext actor, BusinessTypeDraft command);
  OperationResult saveBusinessEnd(ActorContext actor, BusinessEndDraft command);
  OperationResult savePlan(ActorContext actor, PlanDraft command);
  OperationResult saveSku(ActorContext actor, SkuDraft command);
  PublicationResult publishIndustry(ActorContext actor, IndustryPublicationCommand command);
  PublicationResult publishSku(ActorContext actor, SkuPublicationCommand command);
  OperationResult stopSale(ActorContext actor, StopSaleCommand command);
  CatalogView catalog(ActorContext actor, String typeCode);

}
