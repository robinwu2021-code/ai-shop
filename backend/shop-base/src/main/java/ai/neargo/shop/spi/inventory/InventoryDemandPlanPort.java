package ai.neargo.shop.spi.inventory;

import ai.neargo.shop.spi.inventory.InventoryReservationPort.Scope;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Exact demand preserves exclusive finished/recipe branches; explicit finished-with-modifier
 * branches tag each leaf phase.
 */
public interface InventoryDemandPlanPort {
    record Leaf(
            String leafRef,
            String itemRef,
            BigDecimal quantity,
            String unit,
            String definitionHash,
            @JsonInclude(JsonInclude.Include.NON_NULL) String sourceKind) {
        public Leaf(
                String leafRef,
                String itemRef,
                BigDecimal quantity,
                String unit,
                String definitionHash) {
            this(leafRef, itemRef, quantity, unit, definitionHash, null);
        }
    }

    record Branch(
            String sourceComponentRef,
            String fulfillmentRef,
            String storeNo,
            String kind,
            BigDecimal quantity,
            String unit,
            List<Leaf> leaves,
            String recipeRevisionNo) {
        public Branch(
                String sourceComponentRef,
                String fulfillmentRef,
                String storeNo,
                String kind,
                BigDecimal quantity,
                String unit,
                List<Leaf> leaves) {
            this(sourceComponentRef, fulfillmentRef, storeNo, kind, quantity, unit, leaves, null);
        }

        public Branch {
            leaves = List.copyOf(leaves);
        }
    }

    record Plan(Scope scope, String planNo, String planHash, List<Branch> branches) {
        public Plan {
            branches = List.copyOf(branches);
        }
    }

    record Target(String leafRef, BigDecimal cumulativeQty) {}

    record Progress(
            String leafRef,
            String sourceComponentRef,
            String fulfillmentRef,
            String kind,
            String itemRef,
            BigDecimal originalQty,
            BigDecimal issuedQty,
            BigDecimal releasedQty,
            String unit,
            String definitionHash,
            String documentNo,
            String ledgerRef,
            Long costMinor,
            String currency,
            String valuationStatus) {}

    record Receipt(
            Scope scope,
            String planNo,
            String planHash,
            String status,
            int lifecycleVersion,
            Instant expiresAt,
            List<Progress> lines) {
        public Receipt {
            lines = List.copyOf(lines);
        }
    }

    enum WithdrawalStatus {
        WITHDRAWN_ABSENT,
        WITHDRAWN_RESERVED,
        ALREADY_WITHDRAWN,
        ALREADY_EXPIRED
    }

    record WithdrawalReceipt(
            Scope scope,
            String planNo,
            String planHash,
            String operationRef,
            WithdrawalStatus status,
            String receiptRef) {}

    /**
     * The original owner records withdrawal even before reserve arrives; old providers cannot
     * manufacture this proof.
     */
    default WithdrawalReceipt withdraw(Plan plan, String operationRef, int lifecycleVersion) {
        throw new UnsupportedOperationException("Inventory plan withdrawal is not installed");
    }

    Receipt reserve(Plan plan, long ttlSeconds, int lifecycleVersion);

    Receipt query(Scope scope);

    Receipt acceptUnpaid(Scope scope);

    Receipt allocatePaid(Scope scope);

    /**
     * Same target value denotes an operation delta here; the owner advances cumulative progress
     * atomically.
     */
    Receipt issue(
            Scope scope,
            String operationRef,
            String planHash,
            String phase,
            List<Target> quantities);

    Receipt issueTo(
            Scope scope, String operationRef, String planHash, String phase, List<Target> targets);

    Receipt releaseTo(Scope scope, String operationRef, String planHash, List<Target> targets);

    Receipt supplement(Scope scope, String operationRef, String planHash, List<Target> quantities);

    Receipt reverse(Scope scope, String operationRef, String originalOperationRef, String planHash);
}
