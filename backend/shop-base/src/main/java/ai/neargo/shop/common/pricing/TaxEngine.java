package ai.neargo.shop.common.pricing;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import ai.neargo.shop.common.pricing.TaxAdjustmentResolver.AdjustedLines;

/** Pure versioned VAT arithmetic; trusted source admission remains outside this kernel. */
public final class TaxEngine {
    /** Estimates one frozen scope without granting permission to issue an invoice or consume assets. */
    public ProvisionalTaxResult estimate(List<Line> lines, TaxPolicy policy, Context context) {
        checkPolicy(policy, context);
        return new ProvisionalTaxResult(policy, context, calculateLines(lines, policy.currency(),
                policy.priceMode(), policy.rates(), context.sourceSnapshotHash(), context.sourceVersion()));
    }

    /** Merchant-managed tax uses the same line arithmetic without pretending to verify a legal tax profile. */
    public ConfiguredTaxResult estimateConfigured(List<Line> lines, MerchantTaxSnapshot snapshot,
            String sourceHash, long sourceVersion) {
        Objects.requireNonNull(snapshot);
        lines = PricingRuleCandidate.bounded(lines, 1024);
        if (lines.isEmpty()) throw failure("TAX_LINES_MISSING");
        String currency = lines.getFirst().source().baseAmount().currency();
        var rates = new LinkedHashMap<TaxClass, Integer>();
        rates.put(TaxClass.STANDARD, snapshot.rateBp());
        rates.put(TaxClass.ZERO_RATED, 0);
        rates.put(TaxClass.EXEMPT, null);
        rates.put(TaxClass.OUT_OF_SCOPE, null);
        return new ConfiguredTaxResult(snapshot, currency, calculateLines(lines, currency,
                snapshot.priceTaxMode(), rates, sourceHash, sourceVersion));
    }

    private List<TaxLineResult> calculateLines(List<Line> lines, String currency, PriceTaxMode priceMode,
            Map<TaxClass, Integer> rates, String sourceHash, long sourceVersion) {
        lines = PricingRuleCandidate.bounded(lines, 1024);
        if (lines.isEmpty()) {
            throw failure("TAX_LINES_MISSING");
        }
        for (var line : lines) {
            if (!line.source().baseAmount().currency().equals(currency)) {
                throw failure("TAX_CURRENCY_MISMATCH");
            }
            if (line.priceMode() != priceMode) {
                throw failure("TAX_PRICE_MODE_MISMATCH");
            }
            if (line.taxClass() == TaxClass.UNKNOWN || !rates.containsKey(line.taxClass())) {
                throw failure("TAX_CLASS_UNKNOWN");
            }
        }
        new PricingFacts(currency, sourceHash, sourceVersion,
                lines.stream().map(Line::source).toList());
        var grouped = new LinkedHashMap<String, List<Line>>();
        for (var line : lines) {
            grouped.computeIfAbsent(line.source().taxInvoiceLineRef(), ignored -> new ArrayList<>()).add(line);
        }
        var result = new ArrayList<TaxLineResult>();
        for (var entry : grouped.entrySet()) {
            var first = entry.getValue().getFirst();
            var sources = new ArrayList<PricingUnit>();
            var price = new MoneyMinor(currency, 0);
            for (var line : entry.getValue()) {
                if (line.taxClass() != first.taxClass() || line.priceMode() != first.priceMode()
                        || !line.invoiceBase().equals(first.invoiceBase())) {
                    throw failure("TAX_INVOICE_LINE_CONFLICT");
                }
                sources.add(line.source());
                price = price.add(line.source().baseAmount()).requireJsonSafe();
            }
            if (!price.equals(first.invoiceBase())) {
                throw failure("TAX_INVOICE_LINE_INCOMPLETE");
            }
            var zero = new MoneyMinor(currency, 0);
            var original = new TaxLineResult(entry.getKey(), first.taxClass(), first.priceMode(),
                    rates.get(first.taxClass()), sources, zero, zero, zero, zero);
            result.add(reprice(original, price));
        }
        return List.copyOf(result);
    }

    /** Recomputes only a complete fixed plan; no funding changes or optimization loop occur here. */
    public TaxResult finalizeTax(AdjustedLines adjusted, TaxPolicy policy) {
        Objects.requireNonNull(adjusted);
        var provisional = adjusted.provisional();
        checkPolicy(policy, provisional.context());
        if (!provisional.policy().equals(policy)) {
            throw failure("TAX_SNAPSHOT_POLICY_MISMATCH");
        }
        if (provisional.context().taxPointStatus() != TaxPointStatus.CONFIRMED) {
            throw failure("TAX_POINT_UNCONFIRMED");
        }
        for (var line : adjusted.lines()) {
            if (!reprice(line, line.price()).equals(line)) {
                throw failure("TAX_FINAL_LINE_MISMATCH");
            }
        }
        return new TaxResult(adjusted);
    }

    static void checkPolicy(TaxPolicy policy, Context context) {
        if (policy == null) {
            throw failure("TAX_POLICY_MISSING");
        }
        Objects.requireNonNull(context);
        if (!policy.algorithm().equals("VAT_INVOICE_LINE_HALF_UP_V1")
                && !(policy.algorithm().equals("AE_LINE_FILS_V1") && policy.countryCode().equals("AE")
                        && policy.currency().equals("AED"))) {
            throw failure("TAX_ALGORITHM_UNSUPPORTED");
        }
        if (!policy.countryCode().equals(context.supplierCountry())
                || !policy.countryCode().equals(context.placeCountry())
                || policy.regionCode() != null && (!policy.regionCode().equals(context.supplierRegion())
                        || !policy.regionCode().equals(context.placeRegion()))
                || !policy.allowedContexts().contains(context.contextCode())) {
            throw failure("TAX_POLICY_CONTEXT_MISMATCH");
        }
        if (context.taxPoint().isBefore(policy.effectiveFrom())
                || policy.effectiveTo() != null && !context.taxPoint().isBefore(policy.effectiveTo())) {
            throw failure("TAX_POLICY_EXPIRED");
        }
    }

    static TaxLineResult reprice(TaxLineResult original, MoneyMinor price) {
        price.requireNonNegative().requireJsonSafe();
        if (!price.currency().equals(original.price().currency())) {
            throw failure("TAX_CURRENCY_MISMATCH");
        }
        int rate = original.rateBp() == null ? 0 : original.rateBp();
        long denominator = original.priceMode() == PriceTaxMode.INCLUSIVE ? 10000L + rate : 10000L;
        var numerator = BigInteger.valueOf(price.minor()).multiply(BigInteger.valueOf(rate));
        var rounded = numerator.add(BigInteger.valueOf(denominator / 2))
                .divide(BigInteger.valueOf(denominator)).longValueExact();
        var tax = new MoneyMinor(price.currency(), rounded);
        var net = original.priceMode() == PriceTaxMode.INCLUSIVE ? price.subtract(tax) : price;
        var gross = net.add(tax).requireJsonSafe();
        return new TaxLineResult(original.invoiceLineRef(), original.taxClass(), original.priceMode(),
                original.rateBp(), original.sources(), price, net, tax, gross);
    }

    static MoneyMinor sum(List<TaxLineResult> lines, String currency, int dimension) {
        var total = new MoneyMinor(currency, 0);
        for (var line : lines) {
            total = total.add(switch (dimension) {
                case 0 -> line.net();
                case 1 -> line.tax();
                case 2 -> line.gross();
                default -> throw new IllegalArgumentException("Unknown monetary dimension");
            });
        }
        return total.requireNonNegative().requireJsonSafe();
    }

    static TaxFailure failure(String code) {
        return new TaxFailure(code);
    }

    public enum TaxPointStatus { ESTIMATED, CONFIRMED }

    /** Registration and jurisdiction references are backend-admitted facts, never client selections. */
    public record Context(String supplierCountry, String supplierRegion, String placeCountry, String placeRegion,
            String supplierTaxRegistrationRef, String contextCode, Instant taxPoint, TaxPointStatus taxPointStatus,
            String sourceSnapshotHash, long sourceVersion) {
        public Context {
            TaxPolicy.requireCountry(supplierCountry);
            TaxPolicy.requireCountry(placeCountry);
            if (supplierRegion != null) {
                PricingPortion.requireIdentity(supplierRegion, 32);
            }
            if (placeRegion != null) {
                PricingPortion.requireIdentity(placeRegion, 32);
            }
            PricingPortion.requireIdentity(supplierTaxRegistrationRef, 64);
            PricingPortion.requireIdentity(contextCode, 64);
            Objects.requireNonNull(taxPoint);
            Objects.requireNonNull(taxPointStatus);
            if (sourceSnapshotHash == null || !sourceSnapshotHash.matches("[0-9a-f]{64}") || sourceVersion < 0) {
                throw new IllegalArgumentException("Source snapshot/version required");
            }
        }
    }

    /** Owner-confirmed complete invoice base prevents partial AA scopes from recalculating an original line. */
    public record Line(PricingUnit source, TaxClass taxClass, PriceTaxMode priceMode, MoneyMinor invoiceBase) {
        public Line {
            Objects.requireNonNull(source);
            Objects.requireNonNull(invoiceBase).requireNonNegative().requireJsonSafe();
            if (!invoiceBase.currency().equals(source.baseAmount().currency())) {
                throw new IllegalArgumentException("Original invoice and source currencies must match");
            }
            Objects.requireNonNull(taxClass);
            Objects.requireNonNull(priceMode);
        }
    }

    /** Only the engine can produce a provisional snapshot consumed by the adjustment resolver. */
    public static final class ProvisionalTaxResult {
        private final TaxPolicy policy;
        private final Context context;
        private final List<TaxLineResult> lines;

        private ProvisionalTaxResult(TaxPolicy policy, Context context, List<TaxLineResult> lines) {
            this.policy = policy;
            this.context = context;
            this.lines = List.copyOf(lines);
            invoiceNet();
            invoiceTax();
            invoiceGross();
        }

        public TaxPolicy policy() { return policy; }
        public Context context() { return context; }
        public List<TaxLineResult> lines() { return lines; }
        public MoneyMinor invoiceNet() { return sum(lines, policy.currency(), 0); }
        public MoneyMinor invoiceTax() { return sum(lines, policy.currency(), 1); }
        public MoneyMinor invoiceGross() { return sum(lines, policy.currency(), 2); }
    }

    /** Backend configuration calculation; not a payment confirmation or a legally finalized invoice. */
    public static final class ConfiguredTaxResult {
        private final MerchantTaxSnapshot snapshot;
        private final String currency;
        private final List<TaxLineResult> lines;

        private ConfiguredTaxResult(MerchantTaxSnapshot snapshot, String currency, List<TaxLineResult> lines) {
            this.snapshot = snapshot;
            this.currency = currency;
            this.lines = List.copyOf(lines);
            invoiceNet();
            invoiceTax();
            invoiceGross();
        }

        public MerchantTaxSnapshot snapshot() { return snapshot; }
        public List<TaxLineResult> lines() { return lines; }
        public MoneyMinor invoiceNet() { return sum(lines, currency, 0); }
        public MoneyMinor invoiceTax() { return sum(lines, currency, 1); }
        public MoneyMinor invoiceGross() { return sum(lines, currency, 2); }
    }

    /** Failure never contains a partial monetary result that a caller could display or settle. */
    public static final class TaxFailure extends IllegalArgumentException {
        private final String code;

        public TaxFailure(String code) {
            super(code);
            this.code = Objects.requireNonNull(code);
        }

        public String code() { return code; }
    }
}
