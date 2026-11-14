package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.trade.PricingFeeFacts.Kind;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public final class FoodFeeSourceContracts {
    private FoodFeeSourceContracts() {}

    /** References/physical facts only. CORE owns rule amounts and taxes. */
    public record BindReq(
            long expectedVersion,
            String checkNo,
            @NotBlank String ruleNo,
            @NotBlank String revisionNo,
            @NotNull Kind kind,
            @NotBlank String unit,
            BigDecimal packageQuantity,
            List<String> basisSourceRefs,
            String waiverEvidence,
            @NotBlank String reason,
            boolean enabled,
            String contractScopeNo) {
        public BindReq(
                long expectedVersion,
                String checkNo,
                String ruleNo,
                String revisionNo,
                Kind kind,
                String unit,
                BigDecimal packageQuantity,
                List<String> basisSourceRefs,
                String waiverEvidence,
                String reason,
                boolean enabled) {
            this(
                    expectedVersion,
                    checkNo,
                    ruleNo,
                    revisionNo,
                    kind,
                    unit,
                    packageQuantity,
                    basisSourceRefs,
                    waiverEvidence,
                    reason,
                    enabled,
                    null);
        }

        public BindReq {
            basisSourceRefs = basisSourceRefs == null ? null : List.copyOf(basisSourceRefs);
        }
    }

    public record BindingVO(
            String bindingNo,
            String checkNo,
            String ruleNo,
            String revisionNo,
            Kind kind,
            String unit,
            BigDecimal packageQuantity,
            List<String> basisSourceRefs,
            String waiverEvidence,
            boolean enabled,
            long version,
            String contractScopeNo) {
        public BindingVO(
                String bindingNo,
                String checkNo,
                String ruleNo,
                String revisionNo,
                Kind kind,
                String unit,
                BigDecimal packageQuantity,
                List<String> basisSourceRefs,
                String waiverEvidence,
                boolean enabled,
                long version) {
            this(
                    bindingNo,
                    checkNo,
                    ruleNo,
                    revisionNo,
                    kind,
                    unit,
                    packageQuantity,
                    basisSourceRefs,
                    waiverEvidence,
                    enabled,
                    version,
                    null);
        }
    }
}
