package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;
import java.util.List;

/**
 * Original-owner channel admission is distinct from PREPAY/POSTPAY and never acknowledges local
 * payment.
 */
public interface IndustryChannelOrderPort {
    record Scope(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String tenantNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String storeNo) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown original channel scope field");
        }
    }

    /**
     * The source hash binds original immutable business data; importRef is stable across all
     * preparation generations.
     */
    record Reference(
            Scope scope,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String sourceSystem,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String sourceNo,
            @JsonProperty(value = "sourceVersion", required = true)
                    @JsonDeserialize(using = CatalogRevisionDeserializer.class)
                    long sourceVersion,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String sourceHash,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String importRef) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown original channel reference field");
        }
    }

    enum Status {
        WAITING_FUNDING,
        WAITING_STOCK,
        PREPARING,
        PREPARED,
        CONFIRMING,
        CANCELLING,
        ACCEPTED,
        CANCELLED,
        RECOVERY_REQUIRED
    }

    record Preparation(
            Reference reference,
            Status status,
            String orderNo,
            String subOrderNo,
            String preparationReceiptRef,
            Instant validUntil,
            String fundingStatus,
            Plan inventoryPlan,
            String quoteNo) {
        public Preparation(
                Reference reference,
                Status status,
                String orderNo,
                String subOrderNo,
                String preparationReceiptRef,
                Instant validUntil,
                String fundingStatus,
                Plan inventoryPlan) {
            this(
                    reference,
                    status,
                    orderNo,
                    subOrderNo,
                    preparationReceiptRef,
                    validUntil,
                    fundingStatus,
                    inventoryPlan,
                    null);
        }
    }

    /**
     * Amount allocations belong to the original core owner; opaque item references are not public
     * database IDs.
     */
    record Allocation(
            String externalLineRef,
            String parentExternalLineRef,
            String externalKind,
            String sourceLineRef,
            String componentRef,
            String coreItemRef,
            String physicalQuantity,
            String unit,
            long amountMinor,
            String currency,
            String receiptRef,
            long orderItemId) {}

    record Acceptance(
            Reference reference,
            String orderNo,
            String subOrderNo,
            String acceptanceReceiptRef,
            String fundingStatus,
            List<Allocation> allocations) {
        public Acceptance {
            allocations = List.copyOf(allocations);
        }
    }

    record Cancellation(Reference reference, String cancellationReceiptRef) {}

    /**
     * Captures original source facts and reserves real receivable/inventory readiness with the same
     * import reference.
     */
    Preparation prepare(Reference reference);

    /**
     * Queries/reconciles the original intent; it does not create a replacement order or send a
     * platform action.
     */
    Preparation get(Reference reference);

    /**
     * Reads the original source's platform acceptance proof; a caller-supplied paid boolean is
     * never accepted.
     */
    Acceptance confirm(Reference reference);

    /** Requires original withdrawal authority and only cancels an unaccepted preparation. */
    Cancellation cancel(Reference reference);
}
