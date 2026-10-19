package ai.neargo.shop.trade.dto;

import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.AssetKind;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.PreviewView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseCommand;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort.UseSourceFact;
import java.time.Instant;
import java.time.LocalDate;

/** Internal recovery data only; public views must explicitly select safe fields. */
public record ServiceOrderSnapshot(String serviceOrderNo, String operationNo, String status,
        UseSourceFact fact, EntitlementContext context, PreviewBinding preview, UseCommand command,
        DecisionRef decision, Instant deadline, String cancellationReason) {
    public record PreviewBinding(AssetKind kind, PreviewView preview, LocalDate serviceDay) { }
}
