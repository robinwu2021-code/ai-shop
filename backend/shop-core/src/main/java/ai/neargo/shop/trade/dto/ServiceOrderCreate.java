package ai.neargo.shop.trade.dto;

import ai.neargo.shop.spi.entitlement.ContractChecks;
import java.util.List;

/** A current counter service, identified by a scoped member reference; price and service time are server facts. */
public record ServiceOrderCreate(String customerRef, String storeNo, List<Line> items) {
    public ServiceOrderCreate {
        ContractChecks.require(customerRef != null && !customerRef.isBlank() && customerRef.length() <= 256);
        ContractChecks.id(storeNo); items = List.copyOf(ContractChecks.present(items));
        ContractChecks.require(!items.isEmpty() && items.size() <= 20);
        ContractChecks.require(items.stream().map(Line::skuNo).distinct().count() == items.size());
    }
    public record Line(String skuNo, int quantity) {
        public Line { ContractChecks.id(skuNo); ContractChecks.require(quantity >= 1 && quantity <= 100); }
    }
    @Override public String toString() { return "ServiceOrderCreate[customerRef=REDACTED, storeNo=" + storeNo + "]"; }
}
