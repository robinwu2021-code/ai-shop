package ai.neargo.commerce.core.spi;

public record CreditSlice(
        String ledgerNo,
        String lotNo,
        long amount,
        String parentProvenance,
        String sourceType,
        String sourceRefNo) {}
