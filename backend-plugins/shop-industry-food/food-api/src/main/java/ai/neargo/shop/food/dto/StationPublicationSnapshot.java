package ai.neargo.shop.food.dto;

import ai.neargo.shop.food.dto.StationContracts.StationVO;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import java.util.List;

/** Schema five freezes the station and its complete incoming routing version at publication. */
public record StationPublicationSnapshot(int contentSchema, String configKind, String configNo, long sourceVersion,
        String entityNo, String storeNo, StationVO station, List<Route> routes, List<SkuIdentityFact> skuFacts,
        String approvalRef) {
    public StationPublicationSnapshot { routes = List.copyOf(routes); skuFacts = List.copyOf(skuFacts); }
    public record Route(String skuNo, String taskRole, long routeVersion) { }
}
