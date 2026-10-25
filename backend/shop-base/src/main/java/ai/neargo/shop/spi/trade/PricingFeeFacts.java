package ai.neargo.shop.spi.trade;

import ai.neargo.shop.common.pricing.PricingPortion;
import ai.neargo.shop.common.pricing.TaxClass;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;

import java.math.BigDecimal;
import java.util.List;

/**
 * Fee quantities and source references come from the authenticated industry owner; amounts come
 * from CORE.
 */
public final class PricingFeeFacts {
    private PricingFeeFacts() {}

    public enum Kind {
        TEA,
        HOTPOT,
        PACKAGING,
        SERVICE,
        MINIMUM
    }

    public enum Method {
        PER_QUANTITY,
        FIXED,
        PERCENT,
        MINIMUM
    }

    public record Source(
            String ruleNo,
            String revisionNo,
            String scopeNo,
            Kind kind,
            BigDecimal quantity,
            String unit,
            List<String> basisSourceRefs,
            String waiverEvidence) {
        public Source {
            basisSourceRefs = basisSourceRefs == null ? null : List.copyOf(basisSourceRefs);
        }
    }

    public record Definition(
            Kind kind,
            Method method,
            String unit,
            String currency,
            long unitAmountMinor,
            Integer rateBp,
            TaxClass taxClass) {}

    public record Rule(
            Scope scope,
            String ruleNo,
            String revisionNo,
            long version,
            String status,
            Definition definition) {}

    public record ScopeBasis(
            String scopeNo,
            String ruleNo,
            String currency,
            long goodsBasisMinor,
            long feeMinor,
            long version) {}

    public record Entry(
            PricingPortion portion,
            long amountMinor,
            TaxClass taxClass,
            String invoiceRef,
            String ruleNo,
            String revisionNo,
            String scopeNo) {}

    public record Correction(
            String applicationNo, long currentFeeMinor, long targetFeeMinor, String sourceHash) {}

    public record Resolved(
            Source source,
            Rule rule,
            ScopeBasis previous,
            long currentGoodsBasisMinor,
            long targetFeeMinor,
            long additionalFeeMinor,
            List<Entry> entries,
            List<Correction> corrections) {
        public Resolved {
            entries = List.copyOf(entries);
            corrections = List.copyOf(corrections);
        }
    }

    public record Plan(List<Resolved> fees, String planHash) {
        public Plan {
            fees = List.copyOf(fees);
        }
    }

    public record Application(
            String applicationNo,
            String orderNo,
            String subOrderNo,
            String quoteNo,
            String ruleNo,
            String revisionNo,
            String scopeNo,
            String currency,
            long feeMinor,
            long adjustedFeeMinor,
            String sourceHash) {}
}
