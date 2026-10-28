package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionLookup;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import java.util.Objects;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ExecutionEvidenceLookup;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence;
import ai.neargo.shop.spi.trade.ServiceExecutionEvidencePort.CurrentActor;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.Grant;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.PrepareExecution;

/** Core alone reserves original service units; preparation never starts a human service. */
public interface ServiceExecutionService {
    /** Verify the real frozen purchase and current SELF before reserving one permanent unit. */
    Grant prepare(PrepareExecution request);

    /** A human read rechecks the current original task/person authority. */
    Grant query(ExecutionLookup request);

    /** Read the exact original without manufacturing or impersonating a human action. */
    Grant queryOriginal(ExecutionLookup request);

    /** HUMAN bootstrap mints only the existing exact original under current delegated SELF. */
    ExecutionReference referenceOriginal(ExecutionLookup request);

    /** MACHINE observation never mints authority or installs the original actor. */
    ExecutionOriginalEvidence queryOriginalEvidence(ExecutionEvidenceLookup request);

    /** HUMAN check of the same complete reference immediately before stock owner acceptance. */
    CurrentActor authorizeCurrent(ExecutionEvidenceLookup request);

    /** Observe at most one bounded batch of persisted originals; never prepare or release on a timer. */
    int recoverPending(int limit);

    /** Admission writers call after parent/children and before checkout-operation/declaration locks. */
    void lockSourceLocked(ExecutionCoordinates coordinates);

    /** A real local source mutation suspends grants in its same primary transaction, retaining units. */
    int requestSuspendLocked(ExecutionCoordinates coordinates, String reason);

    /** Internal original roots are derived from the persisted admission, not public command fields. */
    record ExecutionCoordinates(ExecutionScope scope, String caller, String sourceOperationNo,
            String orderNo, String subOrderNo) {
        public ExecutionCoordinates {
            Objects.requireNonNull(scope, "scope");
            if (!"BEAUTY".equals(caller) || !reference(sourceOperationNo)
                    || !reference(orderNo) || !reference(subOrderNo)) {
                throw new IllegalArgumentException("complete original execution coordinates required");
            }
        }
        private static boolean reference(String value) {
            return value != null && !value.isBlank() && value.length() <= 64 && value.equals(value.strip());
        }
    }
}
