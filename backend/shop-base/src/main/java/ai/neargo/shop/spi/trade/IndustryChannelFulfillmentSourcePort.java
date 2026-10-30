package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Scope;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;
import java.util.List;

/**
 * Read-only immutable original RETAIL pickup/handover facts; no shipment, payment or inventory
 * command.
 */
public interface IndustryChannelFulfillmentSourcePort {
    record Query(
            Scope scope,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String sourceOrderRef,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String coreOrderRef,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String fulfillmentNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String revision) {
        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw new IllegalArgumentException(field);
        }
    }

    record Line(
            String sourceLineRef,
            String componentRef,
            String coreItemRef,
            String quantity,
            String unit) {}

    record Data(
            Query query,
            String stage,
            List<Line> lines,
            Instant occurredAt,
            String receiptNo,
            String originalSourceHash,
            String requestHash,
            String planHash,
            String inventoryReceiptHash,
            String importRef) {
        /** Legacy manual source receipts did not expose an automatic CIM import binding. */
        public Data(
                Query query,
                String stage,
                List<Line> lines,
                Instant occurredAt,
                String receiptNo,
                String originalSourceHash,
                String requestHash,
                String planHash,
                String inventoryReceiptHash) {
            this(
                    query,
                    stage,
                    lines,
                    occurredAt,
                    receiptNo,
                    originalSourceHash,
                    requestHash,
                    planHash,
                    inventoryReceiptHash,
                    null);
        }

        public Data {
            lines = List.copyOf(lines);
        }
    }

    record Facts(
            Data data,
            String contentHash,
            Instant observedAt,
            Instant validUntil,
            String verificationReceiptRef) {}

    Facts read(Query query);
}
