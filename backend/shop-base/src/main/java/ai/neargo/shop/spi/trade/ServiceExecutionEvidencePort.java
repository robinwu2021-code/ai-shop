package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.OperatorPersonnelFact;

/** Core's neutral read and distinct current human authorization keep downstream owners source-schema agnostic. */
public interface ServiceExecutionEvidencePort {
    ExecutionOriginalEvidence queryOriginalEvidence(ExecutionEvidenceLookup request);

    /** HUMAN only: recheck delegated SELF/personnel/task assignment after external evidence IO. */
    CurrentActor authorizeCurrent(ExecutionEvidenceLookup request);

    /** Current checked facts are accepted request provenance, never reusable machine authorization. */
    record CurrentActor(String referenceHash, ServiceExecutionPort.ExecutionScope scope, String actorNo,
            String namespaceHash, OperatorPersonnelFact personnel, String authorityHash) {
        public CurrentActor signed() {
            return new CurrentActor(referenceHash, scope, actorNo, namespaceHash, personnel,
                    ExecutionEvidenceContract.digest("EXECUTION_CURRENT_SELF_V1", this, "authorityHash"));
        }

        public void validate(ExecutionReference reference) {
            ExecutionEvidenceContract.require(reference.referenceHash().equals(referenceHash)
                    && reference.scope().equals(scope) && equals(signed()));
        }
    }
}
