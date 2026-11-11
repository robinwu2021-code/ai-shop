package ai.neargo.shop.product.service;

import ai.neargo.shop.spi.product.LocalStockControlPort.Scope;
import ai.neargo.shop.spi.product.RetailReplenishmentTargetPort;
import ai.neargo.shop.spi.product.RetailReplenishmentTargetPort.Target;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort.Inbound;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort.Outbound;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** Physical PLATFORM handover effects. Source facts are resolved by the trusted caller before entry. */
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
