package ai.neargo.shop.spi.entitlement;

import ai.neargo.shop.spi.entitlement.EntitlementUsePort.AssetKind;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderOriginPort.Origin;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUsePort.Lookup;

/** Read original Core consent and verified Pay receipt before acquiring beauty locks. */
public interface ServiceWorkOrderUseFactsPort {
    Fact inspect(Lookup lookup);

    enum State { DRAFT, AUTHORIZED, STAGED, COMMITTED, RELEASED, PROCESSING }

    record Fact(Lookup lookup, String operatorNo, SourceRef source, Origin origin, String sourceHash,
                AssetKind assetKind, long amountMinor, State state, String usageNo) {
        public Fact {
            ContractChecks.present(lookup);
            ContractChecks.id(operatorNo);
            ContractChecks.present(source);
            ContractChecks.present(origin);
            ContractChecks.hash(sourceHash);
            ContractChecks.present(state);
            ContractChecks.require(amountMinor >= 0);
            if (state == State.COMMITTED) ContractChecks.id(usageNo);
        }
    }
}
