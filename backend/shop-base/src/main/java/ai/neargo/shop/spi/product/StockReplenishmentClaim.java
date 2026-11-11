package ai.neargo.shop.spi.product;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.product.LocalStockControlPort.Scope;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort.Outbound;
import java.math.BigDecimal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Signed trusted-host attestation of a durable MAIN registry claim, not public write authorization. */
public record StockReplenishmentClaim(@NotBlank @Size(max = 32) String originalNo,
                                     @Pattern(regexp = "FOOD_LOCAL|PLATFORM") @NotBlank String targetOwner,
                                     @Pattern(regexp = "[a-f0-9]{64}") @NotBlank String bindingHash) {
    public static String binding(Scope scope, String owner, Outbound source, String item, String unit, BigDecimal factor) {
        return IdempotencyScope.requestHash("REPLENISH_SOURCE_V1", scope.tenantNo(), scope.entityNo(), scope.storeNo(),
                owner, source.sourceSystem(), source.sourceOwner(), source.documentNo(), Integer.toString(source.lineNo()),
                source.itemNo(), source.baseUnit(), source.quantity().stripTrailingZeros().toPlainString(),
                source.targetStoreNo(), item, unit, factor.stripTrailingZeros().toPlainString());
    }

    public void requireFood(Scope scope, Outbound source, String item, String unit, BigDecimal factor) {
        if (originalNo == null || originalNo.isBlank() || !"FOOD_LOCAL".equals(targetOwner)
                || !binding(scope, targetOwner, source, item, unit, factor).equals(bindingHash))
            throw BizException.of(ErrorCode.CONFLICT);
    }
}
