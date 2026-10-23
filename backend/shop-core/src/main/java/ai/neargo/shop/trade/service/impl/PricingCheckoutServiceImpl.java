package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.BenefitSelection;
import ai.neargo.shop.common.pricing.PriceTaxMode;
import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.common.pricing.PricingEngine;
import ai.neargo.shop.common.pricing.PricingFormula;
import ai.neargo.shop.common.pricing.PricingPortion;
import ai.neargo.shop.common.pricing.PricingResult;
import ai.neargo.shop.common.pricing.PricingRuleFacts;
import ai.neargo.shop.common.pricing.PricingRuleFacts.AccessContext;
import ai.neargo.shop.common.pricing.PricingRuleFacts.PricingRole;
import ai.neargo.shop.common.pricing.PricingRuleFacts.ScopeStatus;
import ai.neargo.shop.common.pricing.PricingRuleFacts.UnitPolicy;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.common.pricing.TaxEngine;
import ai.neargo.shop.common.promotion.CouponCalculation;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort.Policy;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort.SkuRef;
import ai.neargo.shop.spi.trade.PricingCheckoutPort.LineVO;
import ai.neargo.shop.spi.trade.PricingCheckoutPort.QuoteVO;
import ai.neargo.shop.spi.trade.PricingCheckoutPort.Selection;
import ai.neargo.shop.spi.trade.PricingCheckoutPort.TotalsVO;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Credentials;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.trade.assembler.PricingQuoteAssembler;
import ai.neargo.shop.trade.dto.PricingPreparation.Prepared;
import ai.neargo.shop.trade.dto.PricingQuoteCommands;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Amounts;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Context;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Funding;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Industry;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Intent;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Line;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.LineKind;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Phase;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.SaveReq;
import ai.neargo.shop.trade.service.PricingCheckoutService;
import ai.neargo.shop.trade.service.PricingPreparationService;
import ai.neargo.shop.trade.service.PricingQuoteService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** No-benefit baseline only. An unavailable benefit owner is never replaced by synthetic receipts or amounts. */
@Service
public class PricingCheckoutServiceImpl implements PricingCheckoutService {
    private final PricingPreparationService preparations;
    private final PricingQuoteService quotes;
    private final PricingQuoteAssembler assembler;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    public PricingCheckoutServiceImpl(PricingPreparationService preparations, PricingQuoteService quotes,
            PricingQuoteAssembler assembler) {
        this(preparations, quotes, assembler, Clock.systemUTC());
    }
    public PricingCheckoutServiceImpl(PricingPreparationService preparations, PricingQuoteService quotes,
            PricingQuoteAssembler assembler, Clock clock) {
        this.preparations = preparations; this.quotes = quotes; this.assembler = assembler; this.clock = clock;
    }
    @Override public QuoteVO quoteStaff(Scope scope, String actor, PricingSourceRef source, Selection selection,
            String key) {
        baseline(selection);
        var prepared = preparations.staff(scope, source);
        return save(prepared, actor, key, () -> preparations.confirmStaff(scope, source, prepared.preparationHash()));
    }
    @Override public QuoteVO quoteCustomer(PricingSourceRef source, Credentials credentials, Selection selection,
            String key) {
        baseline(selection);
        var prepared = preparations.customer(source, credentials);
        String actor = customer(prepared, credentials);
        return save(prepared, actor, key,
                () -> preparations.confirmCustomer(source, credentials, prepared.preparationHash()));
    }
    @Override public QuoteVO getStaff(Scope scope, String actor, PricingSourceRef source, String quoteNo) {
        outsideTransaction();
        return read(preparations.staff(scope, source), actor, quoteNo, null);
    }
    @Override public QuoteVO getCustomer(PricingSourceRef source, Credentials credentials, String quoteNo) {
        outsideTransaction();
        var prepared = preparations.customer(source, credentials);
        return read(prepared, customer(prepared, credentials), quoteNo, null);
    }
    @Override public QuoteVO revalidateStaff(Scope scope, String actor, PricingSourceRef source, String quoteNo,
            String digest) {
        outsideTransaction(); hash(digest);
        var prepared = preparations.staff(scope, source);
        read(prepared, actor, quoteNo, digest);
        preparations.confirmStaff(scope, source, prepared.preparationHash());
        return read(prepared, actor, quoteNo, digest);
    }
    @Override public QuoteVO revalidateCustomer(PricingSourceRef source, Credentials credentials, String quoteNo,
            String digest) {
        outsideTransaction(); hash(digest);
        var prepared = preparations.customer(source, credentials);
        read(prepared, customer(prepared, credentials), quoteNo, digest);
        preparations.confirmCustomer(source, credentials, prepared.preparationHash());
        return read(prepared, customer(prepared, credentials), quoteNo, digest);
    }
    private QuoteVO save(Prepared prepared, String actor, String key, Supplier<Prepared> confirm) {
        var context = context(prepared, actor);
        var command = calculate(prepared, actor);
        confirm.get();
        var stored = quotes.save(context, command, key);
        // Source and owner calls deliberately stay outside the quote store's short database transaction.
        confirm.get();
        stored = quotes.get(context, stored.quoteNo());
        var result = view(stored);
        verify(prepared, context, stored, result.confirmationDigest());
        return result;
    }
    private QuoteVO read(Prepared prepared, String actor, String no, String digest) {
        var context = context(prepared, actor);
        var stored = quotes.get(context, no);
        var facts = json.readTree(stored.factsSnapshot());
        // Source authority has already re-authorized the caller. A quote number never grants source access.
        if (!assembler.canonical(prepared.source().source()).equals(
                assembler.canonical(facts.path("source")))) throw conflict();
        if (digest != null) verify(prepared, context, stored, digest);
        return view(stored);
    }
    private void verify(Prepared prepared, Context context, PricingQuoteCommands.QuoteVO stored, String digest) {
        if (!"READY".equals(stored.status()) || !clock.instant().isBefore(Instant.parse(stored.expiresAt()))
                || !digest(stored).equals(digest)) throw conflict();
        var facts = json.readTree(stored.factsSnapshot());
        if (!prepared.preparationHash().equals(facts.path("orchestrationHash").asText())) throw conflict();
        var current = assembler.prepare(context, calculate(prepared, context.actorNo()),
                prepared.rules().config().canonicalJson());
        if (!stored.factsHash().equals(current.factsHash())
                || !stored.requestHash().equals(current.requestHash())
                || !stored.economicsHash().equals(current.economicsHash())) throw conflict();
    }
    private SaveReq calculate(Prepared prepared, String actor) {
        if (!clock.instant().isBefore(prepared.expiresAt())) throw conflict();
        var source = prepared.source();
        var policies = new HashMap<SkuRef, Policy>();
        prepared.goodsPolicies().policies().forEach(p -> policies.put(new SkuRef(p.goodsNo(), p.skuNo()), p));
        var eligibility = source.prices().units().stream().map(unit -> {
            var policy = policies.get(new SkuRef(unit.goodsNo(), unit.skuNo()));
            if (policy == null) throw unsupported();
            // Source owner has already excluded included-only bundle components and supplied exact charged units.
            return new UnitPolicy(PricingPortion.from(unit), policy.promotionEligible(), ScopeStatus.OPEN,
                    null, PricingRole.NONE);
        }).toList();
        var access = new AccessContext(actor, source.scope().storeNo(),
                Objects.requireNonNullElse(source.source().scopeNo(), source.source().sourceNo()),
                "SOURCE_BASE", clock.instant());
        var facts = new PricingRuleFacts(source.prices(), access, eligibility, List.of(), List.of());
        var business = new PricingEngine().calculate(new PricingFormula.Input(facts, prepared.rules().config(),
                List.of(), List.of()));
        if (business.status() != PricingResult.Status.CALCULATED) throw unsupported();
        var bases = new HashMap<String, Long>();
        source.invoiceBases().forEach(b -> bases.put(b.invoiceLineRef(), b.amount().minor()));
        var taxLines = source.prices().units().stream().map(unit -> new TaxEngine.Line(unit,
                policies.get(new SkuRef(unit.goodsNo(), unit.skuNo())).taxClass(), prepared.tax().priceTaxMode(),
                new MoneyMinor(source.prices().currency(),
                        bases.get(unit.taxInvoiceLineRef())))).toList();
        final TaxEngine.ConfiguredTaxResult taxes;
        try { taxes = new TaxEngine().estimateConfigured(taxLines, prepared.tax(),
                source.prices().sourceSnapshotHash(), source.source().sourceVersion()); }
        catch (IllegalArgumentException invalid) { throw unsupported(); }
        var taxByPortion = new HashMap<PricingPortion, Long>();
        for (var invoice : taxes.lines()) {
            var shares = CouponCalculation.allocate(invoice.tax().minor(),
                    invoice.sources().stream().map(u -> u.baseAmount().minor()).toList());
            for (int i = 0; i < invoice.sources().size(); i++) {
                taxByPortion.put(PricingPortion.from(invoice.sources().get(i)), shares.get(i));
            }
        }
        var kinds = new HashMap<PricingPortion, LineKind>();
        source.composition().forEach(c -> kinds.put(PricingPortion.from(c.unit()), switch (c.kind()) {
            case MAIN -> LineKind.GOODS;
            case ADDON -> LineKind.ADDON;
            case BUNDLE_INCLUDED, BUNDLE_ADD, BUNDLE_DIFFERENCE -> LineKind.BUNDLE_COMPONENT;
        }));
        var lines = new ArrayList<Line>();
        for (int i = 0; i < business.lines().size(); i++) {
            var calculated = business.lines().get(i);
            var unit = calculated.source();
            var portion = PricingPortion.from(unit);
            long amount = calculated.remaining().minor();
            long tax = taxByPortion.get(portion);
            long net = prepared.tax().priceTaxMode() == PriceTaxMode.EXCLUSIVE ? amount : amount - tax;
            long gross = Math.addExact(net, tax);
            var amounts = new Amounts(unit.baseAmount().minor(), 0, 0, 0, 0, 0, 0, 0, net, tax, gross, 0);
            lines.add(new Line(unit, kinds.get(portion) == LineKind.GOODS ? null : unit.sourceLineNo(), kinds.get(portion),
                    policies.get(new SkuRef(unit.goodsNo(), unit.skuNo())).taxClass(), amounts,
                    bases.get(unit.taxInvoiceLineRef()), eligibility.get(i)));
        }
        int lifetime = (int) Math.min(120, Duration.between(clock.instant(), prepared.expiresAt()).getSeconds());
        if (lifetime < 1) throw conflict();
        return new SaveReq(source.source(), Industry.valueOf(prepared.rules().industry()), source.channel(),
                Phase.LIVE, prepared.rules().revisionNo(), prepared.rules().templateRevisionNo(),
                prepared.rules().engineSpecVersion(), source.prices(), prepared.tax(),
                new Intent(new BenefitSelection(BenefitSelection.Mode.EXPLICIT, List.of(), List.of()),
                        new Funding(0, 0, 0, null)), lines, List.of(), lifetime, prepared.preparationHash());
    }
    private Context context(Prepared prepared, String actor) {
        var scope = prepared.source().scope();
        var context = new Context(scope.tenantNo(), scope.entityNo(), scope.storeNo(),
                prepared.source().subjectNo(), actor);
        try { assembler.context(context); }
        catch (IllegalArgumentException | NullPointerException invalid) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        return context;
    }
    private String customer(Prepared prepared, Credentials credentials) {
        if (prepared.source().subjectNo() == null) throw unsupported();
        return credentials.buyerNo() == null ? prepared.source().subjectNo() : credentials.buyerNo();
    }
    private QuoteVO view(PricingQuoteCommands.QuoteVO stored) {
        var facts = json.readTree(stored.factsSnapshot()).path("facts");
        var result = json.readTree(stored.resultSnapshot());
        var lines = new ArrayList<LineVO>();
        for (var line : result.path("lines")) {
            var source = line.path("source"); var amounts = line.path("amounts");
            lines.add(new LineVO(source.path("sourceLineNo").asText(), nullable(source.path("componentRef")),
                    source.path("taxInvoiceLineRef").asText(), line.path("kind").asText(),
                    source.path("goodsNo").asText(), source.path("skuNo").asText(),
                    source.path("quantityStart").decimalValue(), source.path("quantityEnd").decimalValue(),
                    amounts.path("goodsMinor").asLong(), amounts.path("netMinor").asLong(),
                    amounts.path("taxMinor").asLong(), amounts.path("receivableMinor").asLong()));
        }
        var a = result.path("amounts");
        long discounts = Math.addExact(Math.addExact(a.path("activityDiscountMinor").asLong(),
                a.path("couponDiscountMinor").asLong()), Math.addExact(a.path("manualDiscountMinor").asLong(),
                Math.addExact(a.path("pointsDiscountMinor").asLong(), a.path("waiverMinor").asLong())));
        var totals = new TotalsVO(a.path("goodsMinor").asLong(), discounts, a.path("netMinor").asLong(),
                a.path("taxMinor").asLong(), a.path("receivableMinor").asLong(),
                result.path("remainingDueMinor").asLong());
        String status = "READY".equals(stored.status()) && !clock.instant().isBefore(Instant.parse(stored.expiresAt()))
                ? "EXPIRED" : stored.status();
        return new QuoteVO(stored.quoteNo(), status, stored.expiresAt(), facts.path("sourceVersion").asLong(),
                facts.path("sourceSnapshotHash").asText(), facts.path("currency").asText(), lines, totals, digest(stored));
    }
    private static String nullable(JsonNode value) { return value.isNull() || value.isMissingNode() ? null : value.asText(); }
    private static String digest(PricingQuoteCommands.QuoteVO quote) {
        return PricingSnapshotHash.of(List.of("PRICING_CONFIRMATION_V1", quote.quoteNo(), quote.economicsHash(),
                quote.expiresAt()));
    }
    private static void baseline(Selection selection) {
        outsideTransaction();
        if (selection == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        if (!selection.couponNos().isEmpty() || selection.storedValueAccountNo() != null
                || selection.pointsToUse() != null) throw unsupported();
    }
    private static void hash(String digest) {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) throw BizException.of(ErrorCode.BAD_REQUEST);
    }
    private static void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unsupported();
    }
    private static BizException conflict() { return BizException.of(ErrorCode.CONFLICT); }
    private static BizException unsupported() { return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED); }
}
