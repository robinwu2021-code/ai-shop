package ai.neargo.commerce.core.spi;

import ai.neargo.commerce.api.common.PlanTier;

import java.util.List;

public record ConversionSource(
        String periodNo,
        long version,
        String planNo,
        PlanTier tier,
        String businessType,
        long basis,
        long originalDuration,
        long remainingDuration,
        long value,
        String sourceRefNo,
        long cashBasis,
        long creditBasis,
        List<CreditSlice> creditSlices) {
    public ConversionSource {
        creditSlices = creditSlices == null ? List.of() : List.copyOf(creditSlices);
    }

    public ConversionSource(
            String periodNo,
            long version,
            String planNo,
            PlanTier tier,
            String businessType,
            long basis,
            long originalDuration,
            long remainingDuration,
            long value,
            String sourceRefNo,
            long cashBasis,
            long creditBasis) {
        this(
                periodNo,
                version,
                planNo,
                tier,
                businessType,
                basis,
                originalDuration,
                remainingDuration,
                value,
                sourceRefNo,
                cashBasis,
                creditBasis,
                List.of());
    }
}
