package ai.neargo.shop.spi.resource;

import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.OperatorPersonnelFact;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.AcceptedStaffRef;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ExecutionReference;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.List;

/**
 * Finite current HUMAN workflow authority. Never broadens the original PREPARED/source authority.
 */
public interface ServiceTaskWorkflowAuthorityPort {
    WorkflowAuthority authorizeCurrent(@Valid WorkflowAuthorityLookup lookup);

    record WorkflowAuthorityLookup(
            @NotNull @Valid ExecutionReference reference,
            @NotBlank String submissionNo,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String frozenHash) {}

    enum Action {
        PAUSE,
        RESUME,
        HANDOVER,
        END,
        CLEAN,
        CANCEL
    }

    /** Expected duration is advisory; MANUAL always requires explicit original confirmation. */
    record CleaningRule(String logicalDemandNo, String mode, int expectedMinutes) {}

    record WorkflowAuthority(
            String schema,
            WorkflowAuthorityLookup lookup,
            Action action,
            String actionNo,
            String actionHash,
            long expectedOccupationRevision,
            DelegatedIdentity actor,
            String canonicalAccountNo,
            OperatorPersonnelFact currentPersonnel,
            List<AcceptedStaffRef> acceptedRoster,
            String replacementResourcePlanJson,
            String replacementResourcePlanHash,
            List<CleaningRule> cleaningRules,
            Instant issuedAt,
            Instant validUntil,
            String proofHash) {
        public WorkflowAuthority {
            acceptedRoster = List.copyOf(acceptedRoster);
            cleaningRules = List.copyOf(cleaningRules);
        }

        public WorkflowAuthority signed() {
            return new WorkflowAuthority(
                    schema,
                    lookup,
                    action,
                    actionNo,
                    actionHash,
                    expectedOccupationRevision,
                    actor,
                    canonicalAccountNo,
                    currentPersonnel,
                    acceptedRoster,
                    replacementResourcePlanJson,
                    replacementResourcePlanHash,
                    cleaningRules,
                    issuedAt,
                    validUntil,
                    ExecutionEvidenceContract.digest(
                            "SERVICE_TASK_WORKFLOW_AUTHORITY_V1", this, "proofHash"));
        }

        public void validate() {
            lookup.reference().validate();
            ExecutionEvidenceContract.require(
                    "SERVICE_TASK_WORKFLOW_AUTHORITY_V1".equals(schema)
                            && equals(signed())
                            && action != null
                            && actor != null
                            && expectedOccupationRevision >= 0
                            && actionNo.equals(lookup.submissionNo())
                            && issuedAt.isBefore(validUntil));
            if (action == Action.HANDOVER) {
                ExecutionEvidenceContract.require(
                        replacementResourcePlanHash.equals(
                                ResourcePlanContract.hash(replacementResourcePlanJson)));
            }
            for (var rule : cleaningRules) {
                ExecutionEvidenceContract.require(
                        rule.logicalDemandNo() != null
                                && rule.expectedMinutes() >= 0
                                && ("NONE".equals(rule.mode()) || "MANUAL".equals(rule.mode())));
            }
        }
    }
}
