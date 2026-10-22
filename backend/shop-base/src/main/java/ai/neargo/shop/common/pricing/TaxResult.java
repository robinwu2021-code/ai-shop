package ai.neargo.shop.common.pricing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.HashSet;
import ai.neargo.shop.common.promotion.CouponCalculation;
import ai.neargo.shop.common.pricing.TaxAdjustmentResolver.AdjustedLines;
import ai.neargo.shop.common.pricing.TaxAdjustmentResolver.FundingPlan;
import ai.neargo.shop.common.pricing.TaxAdjustmentResolver.Trace;
import ai.neargo.shop.common.pricing.TaxEngine.Context;

/** Final tax arithmetic evidence, distinct from a fiscal invoice, payment authorization, or best-price claim. */
public final class TaxResult {
    private final TaxPolicy policy;
    private final Context context;
    private final List<TaxLineResult> lines;
    private final List<Trace> traces;
    private final FundingPlan funding;
    private final MoneyMinor considerationOffsets;
    private final MoneyMinor customerDue;
    private final MoneyMinor unassignedDue;

    TaxResult(AdjustedLines adjusted) {
        policy = adjusted.provisional().policy();
        context = adjusted.provisional().context();
        lines = List.copyOf(adjusted.lines());
        traces = List.copyOf(adjusted.traces());
        funding = adjusted.funding();
        considerationOffsets = adjusted.considerationOffsets();
        customerDue = invoiceGross().subtract(considerationOffsets).requireNonNegative().requireJsonSafe();
        unassignedDue = customerDue.subtract(funding.storedPlan()).subtract(funding.otherPaymentPlans())
                .requireNonNegative().requireJsonSafe();
    }

    public TaxPolicy policy() { return policy; }
    public Context context() { return context; }
    public List<TaxLineResult> lines() { return lines; }
    public List<Trace> traces() { return traces; }
    public FundingPlan funding() { return funding; }
    public MoneyMinor considerationOffsets() { return considerationOffsets; }
    public MoneyMinor customerDue() { return customerDue; }
    public MoneyMinor unassignedDue() { return unassignedDue; }
    public MoneyMinor invoiceNet() { return TaxEngine.sum(lines, policy.currency(), 0); }
    public MoneyMinor invoiceTax() { return TaxEngine.sum(lines, policy.currency(), 1); }
    public MoneyMinor invoiceGross() { return TaxEngine.sum(lines, policy.currency(), 2); }

    /** Distinct taxable zero, exempt and out-of-scope categories survive reporting. */
    public List<Summary> summaries() {
        var grouped = new LinkedHashMap<TaxClass, List<TaxLineResult>>();
        for (var line : lines) {
            grouped.computeIfAbsent(line.taxClass(), ignored -> new ArrayList<>()).add(line);
        }
        return grouped.entrySet().stream().map(entry -> new Summary(entry.getKey(),
                entry.getValue().getFirst().rateBp(), TaxEngine.sum(entry.getValue(), policy.currency(), 0),
                TaxEngine.sum(entry.getValue(), policy.currency(), 1),
                TaxEngine.sum(entry.getValue(), policy.currency(), 2))).toList();
    }

    /** Allocates an already frozen invoice line, never applying today's VAT policy again on AA parts. */
    public List<InvoiceShare> allocateInvoiceLine(String invoiceLineRef, List<Share> shares) {
        shares = PricingRuleCandidate.bounded(shares, 64);
        var line = lines.stream().filter(item -> item.invoiceLineRef().equals(invoiceLineRef))
                .findFirst().orElseThrow(() -> TaxEngine.failure("TAX_SHARE_LINE_MISSING"));
        var total = new MoneyMinor(policy.currency(), 0);
        var seen = new HashSet<String>();
        for (var share : shares) {
            if (!seen.add(share.shareRef())) {
                throw TaxEngine.failure("TAX_SHARE_DUPLICATE");
            }
            total = total.add(share.gross()).requireJsonSafe();
        }
        if (shares.isEmpty() || !total.equals(line.gross())) {
            throw TaxEngine.failure("TAX_SHARE_TOTAL_MISMATCH");
        }
        var taxes = CouponCalculation.allocate(line.tax().minor(),
                shares.stream().map(share -> share.gross().minor()).toList());
        var result = new ArrayList<InvoiceShare>();
        for (int i = 0; i < shares.size(); i++) {
            var share = shares.get(i);
            var tax = new MoneyMinor(policy.currency(), taxes.get(i));
            result.add(new InvoiceShare(share.shareRef(), policy, context, line,
                    share.gross().subtract(tax).requireNonNegative(), tax, share.gross()));
        }
        return List.copyOf(result);
    }

    /** Complete owner-frozen gross split; an incremental or partial AA list cannot conserve original tax. */
    public record Share(String shareRef, MoneyMinor gross) {
        public Share {
            PricingPortion.requireIdentity(shareRef, 64);
            Objects.requireNonNull(gross).requireNonNegative().requireJsonSafe();
        }
    }

    /** The original policy, tax point and complete invoice line travel with every frozen AA allocation. */
    public record InvoiceShare(String shareRef, TaxPolicy policy, Context context, TaxLineResult invoiceLine,
            MoneyMinor net, MoneyMinor tax, MoneyMinor gross) {
    }

    /** Reporting retains the legal distinction between zero-rated and non-taxable categories. */
    public record Summary(TaxClass taxClass, Integer rateBp, MoneyMinor net, MoneyMinor tax, MoneyMinor gross) {
    }
}
