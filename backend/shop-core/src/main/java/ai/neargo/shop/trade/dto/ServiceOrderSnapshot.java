package ai.neargo.shop.trade.dto;

import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.AssetKind;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.PreviewView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseCommand;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort.UseSourceFact;
import ai.neargo.shop.spi.storedvalue.StoredValueUseQuotePort.Quote;
import java.time.Instant;
import java.time.LocalDate;

/** Internal recovery data only; public views must explicitly select safe fields. */
public record ServiceOrderSnapshot(String serviceOrderNo, String operationNo, String status,
        UseSourceFact fact, EntitlementContext context, PreviewBinding preview, UseCommand command,
        DecisionRef decision, Instant deadline, String cancellationReason) {
    public record PreviewBinding(AssetKind kind, PreviewView preview, LocalDate serviceDay, Quote moneyQuote) {
        public PreviewBinding(AssetKind kind, PreviewView preview, LocalDate serviceDay) {
            this(kind, preview, serviceDay, null);
        }
        public String ref() { return moneyQuote == null ? preview.previewRef() : moneyQuote.quoteRef(); }
        public String digest() { return moneyQuote == null ? preview.snapshotHash() : moneyQuote.snapshotHash(); }
        public Instant expiresAt() { return moneyQuote == null ? preview.expiresAt() : moneyQuote.expiresAt(); }
        public long assetRevision() { return moneyQuote == null ? preview.assetRevision() : moneyQuote.assetRevision(); }
        public long amountMinor() { return moneyQuote == null ? 0 : moneyQuote.amountMinor(); }
        public String instanceNo() { return moneyQuote == null ? preview.instanceNo() : null; }
        public String accountNo() { return moneyQuote == null ? null : moneyQuote.accountNo(); }
    }
}
