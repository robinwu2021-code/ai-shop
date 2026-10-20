package ai.neargo.shop.spi.entitlement;

import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort.ReserveSale;

/** Core supplies frozen sale facts, including cancellation truth, before pay acquires any write lock. */
public interface EntitlementSaleSourcePort {
    boolean supports(SourceRef source);
    SaleSourceFact saleSource(EntitlementContext context, ReserveSale command);
    record SaleSourceFact(ReserveSale binding, String currency, String ruleSnapshot, boolean canReserve,
                          SourceDecisionPort.SourceDecision decision) {
        public SaleSourceFact {
            ContractChecks.present(binding); ContractChecks.currency(currency); ContractChecks.present(ruleSnapshot);
            ContractChecks.present(decision);
            ContractChecks.require(binding.sourceRef().equals(decision.source()));
        }
    }
}
