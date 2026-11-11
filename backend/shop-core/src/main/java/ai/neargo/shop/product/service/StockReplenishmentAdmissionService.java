package ai.neargo.shop.product.service;

import ai.neargo.shop.spi.product.LocalStockControlPort.Scope;
import ai.neargo.shop.spi.product.RetailReplenishmentTargetPort.Target;
import ai.neargo.shop.spi.product.StockReplenishmentClaim;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort.Checkpoint;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort.Outbound;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import java.math.BigDecimal;

/** Source admission only. Quantity effects remain with each original stock executor. */
public interface StockReplenishmentAdmissionService {
    record Mapping(@NotBlank @Size(max = 32) String itemNo, @NotBlank @Size(max = 16) String baseUnit,
                   @NotNull @DecimalMin("0.000001") @Digits(integer = 18, fraction = 6) BigDecimal factor) { }
    record Change(@NotNull Boolean enabled, @Min(0) long expectedVersion,
                  @NotBlank @Size(max = 256) String reason) { }
    record Connection(boolean enabled, long generation, long revision, String sourceSystem,
                      String sourceOwner, long postingWatermark) { }
    record Original(String originalNo, String targetOwner, Outbound source, Mapping mapping, Target target,
                    BigDecimal expectedQuantity, BigDecimal receivedQuantity, String status) { }

    StockReplenishmentClaim claimFood(Scope scope, String actor, Outbound source, @Valid Mapping mapping);
    StockReplenishmentClaim claimFood(Scope scope, String actor, Outbound source, @Valid Mapping mapping,
                                     boolean manualReceipt);
    Original registerRetail(Scope scope, String actor, String key, Outbound source, @Valid Mapping mapping);
    Original recoverRegistration(Scope scope, String actor, String key, String document, int line, Mapping mapping);
    Original getRetail(Scope scope, String originalNo);
    Connection configureRetail(Scope scope, String actor, String key, @Valid Change change, Checkpoint source);
    Connection connection(Scope scope);
}
