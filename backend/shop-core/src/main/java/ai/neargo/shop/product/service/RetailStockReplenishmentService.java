package ai.neargo.shop.product.service;

import ai.neargo.shop.spi.product.LocalStockControlPort.Scope;
import ai.neargo.shop.spi.product.RetailReplenishmentTargetPort;
import ai.neargo.shop.spi.product.RetailReplenishmentTargetPort.Target;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort.Inbound;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort.Outbound;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Physical PLATFORM handover effects. Overloads taking only source numbers are the public entry: they replay a
 * completed original key first and fetch trusted source facts outside the local transaction.
 */
public interface RetailStockReplenishmentService {
    record View(StockReplenishmentAdmissionService.Original original, BigDecimal differenceQuantity,
                BigDecimal returnedQuantity, BigDecimal returnAcceptedQuantity, String closeReason) { }
    record Adjustment(@NotBlank @Size(max=32) String itemNo, @NotBlank @Size(max=16) String baseUnit,
                      @Min(-2147483647) int delta, @NotNull Target target,
                      @NotBlank @Size(max=256) String reason) { }
    record AdjustmentResult(String movementNo, Target target, int delta, int onHandBefore, int onHandAfter,
                            int reservedQuantity) { }
    record Reconciliation(View original, BigDecimal receivedLedgerQuantity, BigDecimal manualQuantity,
                          BigDecimal returnedLedgerQuantity, BigDecimal acceptedReturnQuantity,
                          RetailReplenishmentTargetPort.Inspection current, List<String> issues) {
        public Reconciliation { issues=List.copyOf(issues); }
    }

    /** Cursor is the last original number; database ids never leave the service. */
    record Page(List<View> items, String nextCursor) {
        public Page { items=List.copyOf(items); }
    }

    StockReplenishmentAdmissionService.Connection connection(Scope scope);
    StockReplenishmentAdmissionService.Connection configure(Scope scope, String actor, String key,
            @NotNull @Valid StockReplenishmentAdmissionService.Change change);
    Page list(Scope scope, @Size(max=32) String after, @Min(1) @Max(100) int size, boolean attentionOnly);
    View register(Scope scope, String actor, String key, @NotBlank @Size(max=32) String documentNo, @Min(1) int lineNo,
                  @NotNull @Valid StockReplenishmentAdmissionService.Mapping mapping);
    View ackReturn(Scope scope, String actor, String key, @NotBlank String originalNo,
                   @NotBlank @Size(max=32) String documentNo, @Min(1) int lineNo);
    AdjustmentResult adjust(Scope scope, String actor, String key, @NotBlank @Size(max=32) String itemNo,
                            @NotBlank @Size(max=16) String baseUnit, @Min(-2147483647) int delta,
                            @NotBlank @Size(max=256) String reason);
    Reconciliation reconcile(Scope scope, String actor, @NotBlank String originalNo);

    View get(Scope scope, @NotBlank String originalNo);
    View receive(Scope scope, String actor, String key, @NotBlank String originalNo, @Min(1) int cumulative);
    View close(Scope scope, String actor, String key, @NotBlank String originalNo,
               @NotBlank @Size(max=256) String reason);
    View dispatchReturn(Scope scope, String actor, String key, @NotBlank String originalNo, @Min(1) int cumulative,
                        @NotBlank @Size(max=256) String reason);
    View ackReturn(Scope scope, String actor, String key, @NotBlank String originalNo, @NotNull Inbound source);
    AdjustmentResult adjust(Scope scope, String actor, String key, @NotNull @Valid Adjustment adjustment);
    View adoptManual(Scope scope, String actor, String key, @NotBlank String originalNo,
                     @NotBlank @Size(max=32) String movementNo);
    Reconciliation reconcile(Scope scope, @NotBlank String originalNo, Outbound verifiedSource);
}
