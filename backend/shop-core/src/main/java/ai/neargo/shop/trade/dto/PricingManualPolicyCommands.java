package ai.neargo.shop.trade.dto;

import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.spi.trade.PricingManualAdjustmentPort.StrictInput;
import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Limits express seller authorization only; they do not change source prices or pricing configuration. */
public final class PricingManualPolicyCommands {
    private PricingManualPolicyCommands() { }
    public record Save(@NotNull @Min(-1) Long expectedVersion, @NotNull Boolean enabled,
            @NotNull @Min(0) Long selfLimitMinor, @NotNull @Min(0) Long maxLimitMinor,
            @NotNull @Min(0) @Max(10000) Integer minPayableRateBp) {
        @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
        public static Save decode(JsonNode node) {
            StrictInput.fields(node,Set.of("expectedVersion","enabled","selfLimitMinor","maxLimitMinor",
                    "minPayableRateBp"),Set.of());
            return new Save(StrictInput.integer(node,"expectedVersion",false,-1,Long.MAX_VALUE),
                    StrictInput.bool(node,"enabled"),
                    StrictInput.integer(node,"selfLimitMinor",false,0,MoneyMinor.MAX_JSON_SAFE_MINOR),
                    StrictInput.integer(node,"maxLimitMinor",false,0,MoneyMinor.MAX_JSON_SAFE_MINOR),
                    StrictInput.integer(node,"minPayableRateBp",false,0,10000).intValue());
        }
    }
    public record PolicyVO(String policyNo, String currency, String reasonCode, String kind, boolean enabled,
            long selfLimitMinor, long maxLimitMinor, int minPayableRateBp, long version) { }
    public record Approve(@NotNull @Min(0) Long version) {
        @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
        public static Approve decode(JsonNode node) {
            StrictInput.fields(node,Set.of("version"),Set.of());
            return new Approve(StrictInput.integer(node,"version",false,0,Long.MAX_VALUE));
        }
    }
}
