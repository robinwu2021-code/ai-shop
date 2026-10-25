package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import ai.neargo.shop.spi.product.DishOfferFacts;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Business responsibility only; original order amounts are never current outstanding balance or
 * payment authority.
 */
public final class BusinessShareContracts {
    private BusinessShareContracts() {}

    public record Person(
            @NotBlank @Size(max = 32) String personNo,
            @JsonDeserialize(using = DishOfferFacts.MinorDeserializer.class) Long amountMinor) {
        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException(name);
        }
    }

    public record LineWeight(
            @NotBlank @Size(max = 32) String foodLineNo,
            @NotBlank @Size(max = 32) String personNo,
            @JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long weight) {
        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException(name);
        }
    }

    public record CreateReq(
            @JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long checkVersion,
            @NotBlank String mode,
            @NotNull @Size(min = 1, max = 50) List<@Valid Person> people,
            @Size(max = 1000) List<@Valid LineWeight> lineWeights,
            @NotBlank @Size(max = 255) String reason) {
        public CreateReq {
            if (people != null) people = List.copyOf(people);
            if (lineWeights != null) lineWeights = List.copyOf(lineWeights);
        }

        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException(name);
        }
    }

    public record ConfirmReq(
            @JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long version,
            @NotBlank @Size(min = 64, max = 64) String sourceHash,
            @NotBlank @Size(min = 64, max = 64) String planHash) {
        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException(name);
        }
    }

    public record CancelReq(
            @JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long version,
            @NotBlank @Size(max = 255) String reason) {
        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException(name);
        }
    }

    public record MembershipScope(
            String membershipNo,
            String checkNo,
            String batchNo,
            Long version,
            LocalDateTime attachedAt,
            String operationNo) {}

    public record BatchSource(
            String batchNo,
            String originalCheckNo,
            Long version,
            String bindingNo,
            Long bindingVersion,
            String orderNo,
            String storeOrderNo,
            String frozenRequestHash,
            List<MembershipScope> memberships,
            @JsonInclude(Include.NON_NULL) String amendmentSourceHash) {
        public BatchSource(
                String batchNo,
                String originalCheckNo,
                Long version,
                String bindingNo,
                Long bindingVersion,
                String orderNo,
                String storeOrderNo,
                String hash,
                List<MembershipScope> memberships) {
            this(
                    batchNo,
                    originalCheckNo,
                    version,
                    bindingNo,
                    bindingVersion,
                    orderNo,
                    storeOrderNo,
                    hash,
                    memberships,
                    null);
        }
    }

    public record AllocationSource(
            String allocationNo,
            String batchNo,
            String foodLineNo,
            String sourceLineRef,
            String sourceComponentRef,
            Long orderItemId,
            BigDecimal originalQty,
            String unit,
            String coreSnapshotHash,
            Long allocationVersion,
            Long lineVersion,
            Long selectionVersion,
            String lineSnapshotHash,
            String currency,
            long originalAmountMinor,
            @JsonInclude(Include.NON_NULL) BigDecimal effectiveQty,
            @JsonInclude(Include.NON_NULL) Long effectiveAmountMinor,
            @JsonInclude(Include.NON_NULL) String amendmentSourceHash) {
        public AllocationSource(
                String allocationNo,
                String batchNo,
                String foodLineNo,
                String sourceLineRef,
                String sourceComponentRef,
                Long item,
                BigDecimal qty,
                String unit,
                String hash,
                Long allocationVersion,
                Long lineVersion,
                Long selectionVersion,
                String lineHash,
                String currency,
                long amount) {
            this(
                    allocationNo,
                    batchNo,
                    foodLineNo,
                    sourceLineRef,
                    sourceComponentRef,
                    item,
                    qty,
                    unit,
                    hash,
                    allocationVersion,
                    lineVersion,
                    selectionVersion,
                    lineHash,
                    currency,
                    amount,
                    null,
                    null,
                    null);
        }

        public long businessAmountMinor() {
            return effectiveAmountMinor == null ? originalAmountMinor : effectiveAmountMinor;
        }
    }

    public record SourceSnapshot(
            String entityNo,
            String storeNo,
            String checkNo,
            Long checkVersion,
            String currency,
            long originalAmountMinor,
            List<BatchSource> batches,
            List<AllocationSource> allocations,
            @JsonInclude(Include.NON_NULL) Long effectiveAmountMinor) {
        public SourceSnapshot(
                String entityNo,
                String storeNo,
                String checkNo,
                Long checkVersion,
                String currency,
                long amount,
                List<BatchSource> batches,
                List<AllocationSource> allocations) {
            this(
                    entityNo,
                    storeNo,
                    checkNo,
                    checkVersion,
                    currency,
                    amount,
                    batches,
                    allocations,
                    null);
        }
    }

    public record ShareCell(
            String allocationNo,
            String foodLineNo,
            Long orderItemId,
            String personNo,
            long amountMinor) {}

    public record PersonTotal(String personNo, long amountMinor) {}

    public record Division(List<PersonTotal> people, List<ShareCell> cells) {
        public Division {
            people = List.copyOf(people);
            cells = List.copyOf(cells);
        }
    }

    public record PlanVO(
            String planNo,
            String checkNo,
            long version,
            String status,
            String storedStatus,
            String mode,
            String currency,
            long originalAmountMinor,
            String sourceHash,
            String planHash,
            boolean localSourcesCurrent,
            boolean settlementEligible,
            boolean financialAuthorityPending,
            SourceSnapshot source,
            Division division,
            CreateReq definition,
            String reason,
            String createdBy,
            String confirmedBy,
            String cancelledBy,
            @JsonInclude(Include.NON_NULL) Long effectiveAmountMinor) {
        public PlanVO(
                String planNo,
                String checkNo,
                long version,
                String status,
                String storedStatus,
                String mode,
                String currency,
                long amount,
                String sourceHash,
                String planHash,
                boolean current,
                boolean eligible,
                boolean pending,
                SourceSnapshot source,
                Division division,
                CreateReq definition,
                String reason,
                String createdBy,
                String confirmedBy,
                String cancelledBy) {
            this(
                    planNo,
                    checkNo,
                    version,
                    status,
                    storedStatus,
                    mode,
                    currency,
                    amount,
                    sourceHash,
                    planHash,
                    current,
                    eligible,
                    pending,
                    source,
                    division,
                    definition,
                    reason,
                    createdBy,
                    confirmedBy,
                    cancelledBy,
                    null);
        }
    }
}
