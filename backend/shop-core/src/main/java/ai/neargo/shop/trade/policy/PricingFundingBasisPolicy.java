package ai.neargo.shop.trade.policy;

import ai.neargo.shop.trade.dto.PricingFundingBasis;
import ai.neargo.shop.trade.entity.OrdPricingFundingSource;
import java.nio.charset.StandardCharsets;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Strict journal decoding, not a new source authority or a replacement for current owner checks. */
public final class PricingFundingBasisPolicy {
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private PricingFundingBasisPolicy() { }
    public static PricingFundingBasis read(OrdPricingFundingSource row) {
        String input = row.getBasisSnapshot();
        if (input == null || input.getBytes(StandardCharsets.UTF_8).length > 2097152) {
            throw new IllegalArgumentException("Bounded frozen basis required");
        }
        var value = JSON.readValue(input, PricingFundingBasis.class);
        if (!row.getCurrency().equals(value.benefits().currency())
                || row.getAmountMinor() != value.quoteReceivableMinor()
                || !row.getEconomicsHash().equals(value.quoteEconomicsHash())
                || !row.getTenantNo().equals(value.tax().tenantNo())
                || !row.getEntityNo().equals(value.tax().entityNo())
                || !row.getStoreNo().equals(value.tax().storeNo())) {
            throw new IllegalArgumentException("Frozen basis must match its original journal scope and economics");
        }
        return value;
    }
}
