package ai.neargo.shop.spi.resource;

import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ExecutionReference;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.List;

/**
 * Finite MACHINE read of one exact confirmed booking allocation; it grants no capacity or HUMAN
 * authority.
 */
public interface ResourceBookingExecutionEvidencePort {
    Evidence queryOriginal(@Valid Lookup lookup);

    record Lookup(
            @NotNull @Valid ExecutionReference reference,
            @NotNull ResourceSourceAuthorityPort.Binding binding,
            @NotBlank String holdNo,
            @Positive long leaseRevision,
            @NotBlank String appointmentItemNo,
            @Positive long arrangementRevision,
            @NotBlank String arrangementHash,
            @NotBlank String wholePlanJson,
            @NotBlank String wholePlanHash) {
        public void validate() {
            reference.validate();
            var scope = reference.scope();
            ExecutionEvidenceContract.require(
                    scope.tenantNo().equals(binding.tenantNo())
                            && scope.entityNo().equals(binding.entityNo())
                            && scope.storeNo().equals(binding.storeNo())
                            && "BEAUTY_APPOINTMENT".equals(binding.sourceType())
                            && ("CONFIRM".equals(binding.action())
                                    || "REPLACE".equals(binding.action()))
                            && wholePlanHash.equals(binding.planHash())
                            && wholePlanHash.equals(ResourcePlanContract.hash(wholePlanJson))
                            && leaseRevision > 0
                            && arrangementRevision > 0);
        }
    }

    record Allocation(
            String unitNo,
            String bucketNo,
            String resourceNo,
            String sourceLineRef,
            Instant startAt,
            Instant endAt,
            int quantity,
            long allocationRevision,
            boolean consumed) {}

    record Evidence(
            Lookup lookup,
            String bookingOwnerNo,
            long allocationRevision,
            List<Allocation> allocations,
            Instant observedAt,
            String proofHash) {
        public Evidence {
            allocations = List.copyOf(allocations);
        }

        public Evidence signed() {
            return new Evidence(
                    lookup,
                    bookingOwnerNo,
                    allocationRevision,
                    allocations,
                    observedAt,
                    ExecutionEvidenceContract.digest(
                            "RESOURCE_BOOKING_EXECUTION_ORIGINAL_V1", this, "proofHash"));
        }

        public void validate() {
            lookup.validate();
            ExecutionEvidenceContract.require(
                    equals(signed())
                            && bookingOwnerNo != null
                            && !bookingOwnerNo.isBlank()
                            && allocationRevision > 0
                            && observedAt != null
                            && !allocations.isEmpty()
                            && allocations.size() <= 10000
                            && allocations.stream()
                                    .allMatch(
                                            a ->
                                                    a.allocationRevision() == allocationRevision
                                                            && a.quantity() > 0
                                                            && a.startAt() != null
                                                            && a.endAt() != null
                                                            && a.startAt().isBefore(a.endAt())
                                                            && a.unitNo() != null
                                                            && a.bucketNo() != null
                                                            && a.resourceNo() != null
                                                            && a.sourceLineRef() != null)
                            && allocations.stream().map(Allocation::unitNo).distinct().count()
                                    == allocations.size());
        }
    }
}
