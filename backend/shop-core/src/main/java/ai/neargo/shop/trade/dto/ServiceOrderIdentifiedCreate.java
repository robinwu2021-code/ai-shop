package ai.neargo.shop.trade.dto;

import ai.neargo.shop.spi.entitlement.ContractChecks;
import java.util.List;

/** Internal only: caller must resolve a scoped pay locator before constructing this command. */
public record ServiceOrderIdentifiedCreate(String userNo, String identityDigest, String storeNo,
                                            List<ServiceOrderCreate.Line> items) {
    public ServiceOrderIdentifiedCreate {
        ContractChecks.id(userNo); ContractChecks.hash(identityDigest); ContractChecks.id(storeNo);
        items = List.copyOf(ContractChecks.present(items));
        ContractChecks.require(!items.isEmpty() && items.size() <= 20);
        ContractChecks.require(items.stream().map(ServiceOrderCreate.Line::skuNo).distinct().count() == items.size());
    }
}
