package ai.neargo.shop.spi.entitlement;

import ai.neargo.shop.spi.entitlement.ServiceWorkOrderOriginPort.Origin;

/** Work-order owner owns the local task allocation, independently of asset and cash owners. */
public interface ServiceWorkOrderUsePort {
    Binding reserve(EntitlementContext context, Lookup lookup);
    Binding inspect(Lookup lookup);
    Binding reconcile(Lookup lookup);

    record Lookup(String tenantNo, String entityNo, String storeNo, String operationNo) {
        public Lookup {
            ContractChecks.id(tenantNo);
            ContractChecks.id(entityNo);
            ContractChecks.id(storeNo);
            ContractChecks.id(operationNo);
        }
    }

    enum State { NOT_FOUND, RESERVED, BOUND, RELEASED }

    record Binding(Lookup lookup, String claimNo, Origin origin, String sourceHash, State state,
                   String bindingNo, String usageNo) {
        public Binding {
            ContractChecks.present(lookup);
            ContractChecks.present(state);
            if (state != State.NOT_FOUND) {
                ContractChecks.present(origin);
                ContractChecks.hash(sourceHash);
            }
            if (state == State.RESERVED || state == State.BOUND) ContractChecks.id(claimNo);
            if (state == State.BOUND) {
                ContractChecks.id(bindingNo);
                ContractChecks.id(usageNo);
            }
        }
    }
}
