package ai.neargo.shop.trade.order.service.create;

import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CustomerParty;

/**
 * Built by the owner from locked persisted acceptance; no public request may supply these facts.
 */
public record CounterPurchaseAuthorization(
        ExpectedCheckoutQuote expected,
        CustomerParty party,
        String authorityNamespaceHash,
        String actorNo,
        String actorRealm,
        String actorKind,
        String accountNo) {
    public CounterPurchaseAuthorization {
        if (expected == null
                || party == null
                || authorityNamespaceHash == null
                || !authorityNamespaceHash.matches("[a-f0-9]{64}")
                || actorNo == null
                || actorNo.isBlank()
                || !"MERCHANT".equals(actorRealm)
                || !("MCH".equals(actorKind) || "USR".equals(actorKind))
                || accountNo == null
                || accountNo.isBlank()) {
            throw new IllegalArgumentException("invalid counter purchase authority");
        }
    }
}
