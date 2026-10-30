package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Channel;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Collection;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Delivery;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Fulfillment;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.InvoiceLine;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Totals;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Original FOOD source packet is produced by its installed evidence owner, never submitted by a
 * public checkout.
 */
public record DiningChannelSourceFacts(
        Key key,
        Original original,
        Current current,
        Instant observedAt,
        Instant validUntil,
        String sourceReceiptRef) {
    public record Key(
            String entityNo, String storeNo, String importNo, String originalDocumentHash) {}

    public enum State {
        WAITING_ACCEPTANCE,
        ACCEPTED,
        CANCELLED,
        COMPLETED
    }

    public record PartyDescription(String externalCustomerRef, String displayName) {}

    /** Event timestamps remain absent when the source supplies only an observed current state. */
    public record Current(
            String externalOrderRef,
            String documentHash,
            String financialHash,
            State state,
            Collection collection,
            Long collectedMinor,
            String originalCollectionRef,
            Instant originalAcceptedAt,
            String originalReadReceiptRef,
            String queryOperationNo,
            String queryAttemptRef) {}

    /**
     * Native party/cart/batch/source numbers are deliberately absent; only FOOD persistence may
     * assign them.
     */
    public record Original(
            Channel channel,
            String connectionNo,
            String connectionRevisionNo,
            String externalSnapshotNo,
            String externalOrderRef,
            String documentHash,
            String financialHash,
            String originalReadReceiptRef,
            String currency,
            Collection originalCollection,
            String originalCollectionRef,
            Fulfillment fulfillment,
            PartyDescription party,
            Delivery delivery,
            Instant orderedAt,
            Instant scheduledAt,
            List<InvoiceLine> invoiceLines,
            Totals totals,
            List<Line> lines,
            List<Definition> definitions,
            String catalogHash,
            String catalogPublicationReceiptRef) {
        public Original {
            invoiceLines = List.copyOf(invoiceLines);
            lines = List.copyOf(lines);
            definitions = List.copyOf(definitions);
        }
    }

    public record DefinitionRef(
            String kind,
            String configNo,
            String revisionNo,
            long publicationRevision,
            String contentHash) {}

    public record Definition(
            String kind,
            String configNo,
            String revisionNo,
            long publicationRevision,
            String contentHash,
            String snapshot) {
        public DefinitionRef reference() {
            return new DefinitionRef(kind, configNo, revisionNo, publicationRevision, contentHash);
        }
    }

    public record Line(
            String externalLineRef,
            String menuNo,
            String menuItemNo,
            String menuRevisionNo,
            String goodsNo,
            String skuNo,
            BigDecimal quantity,
            String unit,
            String note,
            List<CartSelectionInput> selections,
            List<CartBundleChoiceInput> bundleChoices,
            List<Component> components) {
        public Line {
            selections = List.copyOf(selections);
            bundleChoices = List.copyOf(bundleChoices);
            components = List.copyOf(components);
        }
    }

    /**
     * logicalKey is a structural source key, not a core item ID or a pre-existing native row
     * number.
     */
    public record Component(
            String logicalKey,
            String externalLineRef,
            String parentExternalLineRef,
            String externalKind,
            String productionKind,
            String goodsNo,
            String skuNo,
            String title,
            String cover,
            String spec,
            BigDecimal physicalQuantity,
            String unit,
            long grossExTaxMinor,
            long merchantDiscountExTaxMinor,
            long taxMinor,
            long netMinor,
            String originalAllocationBasisRef,
            String recipeRevisionNo,
            String selectionGroupNo,
            String selectionSourceKind,
            String selectionSourceNo,
            String bundleChoiceNo) {}
}
